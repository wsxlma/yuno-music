package com.xiaoliu.yuno.core.playback

import android.net.Uri
import android.util.Base64
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.xiaoliu.yuno.core.network.YunoLog
import java.io.ByteArrayOutputStream

/**
 * DRM 上下文 —— 跨层共享（DataSource 拉 manifest 时写入，license 回调读取）。
 *
 * Apple 的 cbcs HLS：KEY 行的 data: URI（16 字节 key ID）与 manifest URL 中的
 * A\<adamId\> 都是"这一首歌"的标识。Media3 顺序加载，二者总是同曲目相邻写入，
 * 所以"最近值"即可可靠匹配 license 请求（并发预载下一首时下一首的值会覆盖，
 * 但真正创建 DRM 会话紧跟在它的 manifest 加载之后，顺序模型下安全）。
 */
object WidevineContext {
    @Volatile private var adamId: String = ""
    @Volatile private var initDataUri: String = ""

    fun update(adamId: String, initDataUri: String) {
        if (adamId.isNotBlank()) this.adamId = adamId
        if (initDataUri.isNotBlank()) this.initDataUri = initDataUri
    }

    fun adamId(): String = adamId

    fun initDataUri(): String = initDataUri
}

/**
 * HLS 清单重写 DataSource —— 全曲解锁的关键一环。
 *
 * Apple 网页播放器的清单是 cbcs（ISO-23001-7）加密，KEY 行写法是网页方言，
 * Media3 解析器不认。本类在把它交给 Media3 之前：
 *   1. `METHOD=ISO-23001-7` → `METHOD=SAMPLE-AES-CENC`（CENC 才走 Widevine DRM）
 *   2. KEY URI 的 16 字节 = **key ID**（真机日志 + 参考开源实现 amdl 实证），
 *      而 Apple 的 license 服务器校验的是
 *      pssh{ systemId=Widevine, data=WidevineCencHeader{ AESCTR + KID } }
 *      —— 裸 16 字节直接包 pssh 会被 errorCode -42585 拒。
 *   3. 记录 adamId / 原 KEY URI 供 license 请求使用（uri 字段原样回传）。
 *
 * 其余数据（分片等）原样透传，URL 不变（清单里的相对路径保持相对原始 URL 解析）。
 */
@UnstableApi
class StreamRewriterDataSource(private val upstream: DataSource) : DataSource {

    private var rewritten: ByteArray? = null
    private var position = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        rewritten = null
        position = 0
        val uri = dataSpec.uri
        val isPlaylist = uri.lastPathSegment?.contains(".m3u8") == true
        if (!isPlaylist) return upstream.open(dataSpec)

        // 清单：整段拉下 → 重写 → 从内存供 Media3 解析
        upstream.open(dataSpec)
        val raw = readAll()
        // ⚠️ 绝不能在这里 close upstream：Media3 的 ParsingLoadable 解析完后还会调
        // getUri()（parse 的入口参数），提前关闭使其状态失效 → 真机 NPE 教训
        // （ParsingLoadable.load:224 checkNotNull(dataSource.getUri()) 崩）。
        // 统一交给本 DataSource 的 close() 关。
        val text = String(raw, Charsets.UTF_8)
        val bytes = rewriteManifest(text, uri.toString()).toByteArray(Charsets.UTF_8)
        if (bytes.size != raw.size) {
            YunoLog.d(
                "HLS",
                "manifest ${uri.lastPathSegment} ${raw.size}B -> ${bytes.size}B (cbcs->widevine)"
            )
            text.lineSequence().firstOrNull { it.startsWith("#EXT-X-KEY") }?.let {
                YunoLog.d("HLS", "original KEY: $it")
            }
            String(bytes, Charsets.UTF_8).lineSequence()
                .firstOrNull { it.startsWith("#EXT-X-KEY") }?.let {
                    YunoLog.d("HLS", "rewritten KEY: $it")
                }
        }
        rewritten = bytes
        return bytes.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val local = rewritten ?: return upstream.read(buffer, offset, length)
        if (position >= local.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, local.size - position)
        System.arraycopy(local, position, buffer, offset, count)
        position += count
        return count
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        rewritten = null
        upstream.close()
    }

    private fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = upstream.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        /** Widevine 的标准 scheme UUID（Media3 以此识别 Widevine 加密流） */
        private const val WIDEVINE_KEYFORMAT = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"

        /** Widevine systemId 的 16 字节形式（进 pssh box 用） */
        private val WIDEVINE_UUID_BYTES = byteArrayOf(
            0xED.toByte(), 0xEF.toByte(), 0x8B.toByte(), 0xA9.toByte(),
            0x79.toByte(), 0xD6.toByte(), 0x4A.toByte(), 0xCE.toByte(),
            0xA3.toByte(), 0xC8.toByte(), 0x27.toByte(), 0xDC.toByte(),
            0xD5.toByte(), 0x1D.toByte(), 0x21.toByte(), 0xED.toByte()
        )

        private val ADAM_ID_RE = Regex("mzaf_A(\\d+)")
        private val KEY_URI_RE = Regex("URI=\"(data:[^\"]+)\"")

        /**
         * 重写 cbcs 清单（Apple 方言 → Media3 标准写法），并记录 DRM 上下文。
         * 无 ISO-23001-7 的行时原样返回（preview 等未加密流完全不受影响）。
         */
        fun rewriteManifest(text: String, manifestUrl: String = ""): String {
            val normalized = text.replace("\r\n", "\n")
            if (!normalized.contains("METHOD=ISO-23001-7")) return normalized
            val adamId = ADAM_ID_RE.find(manifestUrl)?.groupValues?.get(1).orEmpty()
            val out = StringBuilder(normalized.length + 512)
            for (line in normalized.split('\n')) {
                if (line.startsWith("#EXT-X-KEY") && line.contains("METHOD=ISO-23001-7")) {
                    val initDataUri = KEY_URI_RE.find(line)?.groupValues?.get(1).orEmpty()
                    // 原始 URI 原样留给 license 请求（Chrome 请求的 "uri" 参数就是它）
                    WidevineContext.update(adamId, initDataUri)
                    out.append(buildKeyLine(initDataUri))
                } else {
                    out.append(line)
                }
                out.append('\n')
            }
            return out.toString()
        }

        /** 构造标准的 SAMPLE-AES-CENC + Widevine KEY 行 */
        private fun buildKeyLine(initDataUri: String): String {
            // KEY 行的 payload 就是 key ID（16 字节 base64）——
            // 参考 amdl（apple-music-downloader）开源实现，pssh 的 data 是
            // WidevineCencHeader{ algorithm=AESCTR, key_ids=[KID] }，缺省字段省略
            val kid = runCatching {
                android.util.Base64.decode(
                    initDataUri.substringAfter("base64,", ""),
                    android.util.Base64.DEFAULT
                )
            }.getOrNull()
            val uriAttr = if (kid != null && kid.isNotEmpty()) {
                "data:text/plain;base64," +
                    android.util.Base64.encodeToString(buildCencPssh(kid), android.util.Base64.NO_WRAP)
            } else {
                initDataUri
            }
            return "#EXT-X-KEY:METHOD=SAMPLE-AES-CENC,URI=\"$uriAttr\"," +
                "KEYFORMAT=\"$WIDEVINE_KEYFORMAT\",KEYFORMATVERSIONS=\"1\""
        }

        /**
         * 构造 Apple 认可的 Widevine pssh box：
         *   box 头：size + "pssh" + ver0/flags0 + Widevine systemId(16B) + dataLen
         *   data：WidevineCencHeader protobuf ——
         *     08 01                 = field1 algorithm = 1 (AESCTR)
         *     12 10 <16B key ID>    = field2 key_ids = [KID]
         *   （content_id/provider/policy 为空字符串，protobuf 省略零值）
         */
        private fun buildCencPssh(kid: ByteArray): ByteArray {
            val header = ByteArray(2 + 2 + kid.size)
            header[0] = 0x08
            header[1] = 0x01
            header[2] = 0x12
            header[3] = kid.size.toByte()
            System.arraycopy(kid, 0, header, 4, kid.size)

            val out = ByteArrayOutputStream()
            fun writeInt(v: Int) {
                out.write((v ushr 24) and 0xFF)
                out.write((v ushr 16) and 0xFF)
                out.write((v ushr 8) and 0xFF)
                out.write(v and 0xFF)
            }
            writeInt(32 + header.size)
            out.write("pssh".toByteArray(Charsets.US_ASCII))
            writeInt(0)                            // version 0 + flags 0
            out.write(WIDEVINE_UUID_BYTES)         // systemId
            writeInt(header.size)
            out.write(header)
            return out.toByteArray()
        }
    }
}