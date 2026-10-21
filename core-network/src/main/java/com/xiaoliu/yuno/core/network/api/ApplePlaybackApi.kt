package com.xiaoliu.yuno.core.network.api

import com.xiaoliu.yuno.core.network.AudioQuality
import com.xiaoliu.yuno.core.network.DolbyCapability
import com.xiaoliu.yuno.core.network.Quality
import com.xiaoliu.yuno.core.network.Http
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 播放 lease（webPlayback）—— 全曲流的唯一入口。
 *
 * 端点（调研确证）：POST play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback
 *   body: {"salableAdamId": "<catalog songId>", "language": "en-US"}
 *   头部：Bearer dev token + Media-User-Token + Origin
 *   响应：songList[0].assets[].URL = 标准 HLS m3u8（AAC 256 的 flavor = "28:ctrp256"）
 *
 * ⚠️ 重要：该 HLS 流是 Widevine 加密的（全曲保护）。ExoPlayer 直接播会在解密环节
 * 失败 —— 上层（PlaybackController）监听 onPlayerError 自动降级到 30 秒 preview。
 * 全曲解锁需要自接 DrmSessionManager + acquireWebPlaybackLicense（见 README「全曲与降级」）。
 *
 * 错误码（遇到直接返回 null 让上层降级）：
 *   3077 track not found / 3076 content unavailable（storefront 不对）
 *   2002 credentials expired（重新登录）
 */
class ApplePlaybackApi(
    private val auth: AuthStore,
    private val tokenProvider: DevTokenProvider
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetchStreamUrl(songId: String): String? = runCatching {
        val devToken = tokenProvider.developerToken() ?: return@runCatching null
        val userToken = auth.cached.mediaUserToken.takeIf { it.isNotBlank() }
            ?: return@runCatching null

        val body = buildJsonObject {
            put("salableAdamId", songId)
            put("language", "en-US")
        }.toString()

        val resp = Http.postJson(
            "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/webPlayback",
            body,
            mapOf(
                "Authorization" to "Bearer $devToken",
                "Media-User-Token" to userToken,
                "Origin" to "https://music.apple.com"
            )
        )

        val root = json.parseToJsonElement(resp) as? JsonObject ?: return@runCatching null
        val song = (root["songList"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return@runCatching null
        val assets = (song["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: return@runCatching null

        // 资产按优先级排序：AAC 256 优先，其余按码率降序
        val candidates = assets
            .mapNotNull { a ->
                val url = (a["URL"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val flavor = (a["flavor"] as? JsonPrimitive)?.contentOrNull
                Triple(flavor, url, flavorRank(flavor))
            }
            .sortedBy { it.third }

        if (candidates.isEmpty()) {
            YunoLog.e("Lease", "songId=$songId: no asset URL")
            return@runCatching null
        }

        YunoLog.d(
            "Lease",
            "songId=$songId assets=${assets.size} flavors=${candidates.map { it.first }}"
        )

        // ★ manifest 嗅探（2026-10-19 真机 error 定位）：
        // ExoPlayer 报 PARSING_MANIFEST_MALFORMED —— 探明各 flavor 的流到底是什么：
        //   以 #EXTM3U 开头 = 标准 HLS（同时记录头部样本，加密方式就在 #EXT-X-KEY 行里）；
        //   否则把内容样本写进日志（诊断证据）
        val probeReport = StringBuilder()
        var picked: String? = null
        for ((flavor, url, _) in candidates.take(4)) {
            val head = runCatching { Http.get(url, PROBE_HEADERS) }.getOrNull()
            val isHls = head?.trimStart()?.startsWith("#EXTM3U") == true
            probeReport
                .append(flavor).append('=')
                .append(
                    when {
                        head == null -> "ERR"
                        isHls -> "HLS(${head.length}B) sample[${head.take(400).replace("\n", "\\n")}]"
                        else -> "NOT_HLS[${head.take(80).replace('\n', ' ')}]"
                    }
                )
                .append(" | ")
            if (isHls) {
                picked = url
                break
            }
        }
        YunoLog.d("Lease", "songId=$songId probes: $probeReport")

        if (picked == null) {
            // 所有 flavor 都不是标准 HLS → 交给上层降级 preview（日志里已有样本证据）
            return@runCatching null
        }
        picked
    }.getOrElse { e ->
        YunoLog.e("Lease", "songId=$songId failed: ${e.message}")
        null
    }

    /**
     * flavor 排序 —— **数字越小越优先**。
     *
     * 2.0.0 Beta6 改用苹果官方口径（无损 / 高解析度无损 / 杜比全景声）：
     *   1. 开了杜比全景声**且设备能解 E-AC3/AC-4** → 空间音频流排最前（-1）
     *   2. 高解析度无损（192kHz）优先于无损（48kHz）
     *   3. 都没有 → AAC 256 兜底
     *
     * Apple 返回的 flavor 代号是自定义字符串（如 `28:ctrp256`、`30:cbcp256`），
     * 这里按**关键词**匹配而不是硬编码全串 —— 换版本也不会整个失效。
     */
    private fun flavorRank(flavor: String?): Int {
        if (flavor == null) return 99
        val f = flavor.lowercase()

        // ⚠️ 这两条是**真机验证过能播**的优先级，绝对不能被下面的关键词匹配打乱：
        //    28:ctrp256 → 走 StreamRewriterDataSource 重写成 SAMPLE-AES-CENC（全曲）
        //    30:cbcp256 → 次选
        // Beta6 我把它们一起归到"关键词 256"里打成同分，结果选中了不能解的流 → 完全无声。
        // 这里把它们钉死在最前面，音质档位只影响它们**之后**的排序。
        when (flavor) {
            "28:ctrp256" -> return 0
            "30:cbcp256" -> return 1
        }

        val isAtmos = f.contains("atmos") || f.contains("dolby") ||
            f.contains("ec3") || f.contains("ec-3") ||
            f.contains("ac4") || f.contains("ac-4")
        val isHiRes = f.contains("192") || f.contains("hires")
        val isLossless = f.contains("alac") || f.contains("lossless")
        val isAac256 = f.endsWith("256")
        val isAac64 = f.endsWith("64")

        // Atmos 排在钉死的两条**之前**：宁可先试空间音频，试不通探测循环会
        // 接着往下试 ctrp256/cbcp256（fetchStreamUrl 的探测是按排序逐个试的）。
        // 设备解不了 E-AC3/AC-4 时不升 Atmos —— 硬选只会报错跌 preview。
        val atmosPick = AudioQuality.dolbyAtmosEnabled && isAtmos &&
            DolbyCapability.deviceDecodesAtmos()

        return when {
            atmosPick -> -1
            isHiRes && AudioQuality.quality == Quality.HI_RES_LOSSLESS -> 2
            isHiRes -> 3
            isLossless -> 4
            isAac256 -> 6
            isAac64 -> 8
            else -> 50
        }
    }

    private companion object {
        /** 与播放器一致的浏览器身份（探针必须同身份，否则被 Apple 拒） */
        val PROBE_HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/"
        )
    }
}
