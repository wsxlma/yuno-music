package com.xiaoliu.yuno.core.playback

import android.content.Context
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.MediaDrmCallback
import com.xiaoliu.yuno.core.network.Http
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Apple Widevine DRM —— 全曲解锁（cbcs HLS 解密）。
 *
 * 背景（2026-10-19 真机日志实锤）：Apple 全曲流是 ISO-23001-7（cbcs）加密的 fMP4，
 * KEY 行带 16 字节 initData。Apple Music 网页版在 Chrome 上走的就是 Widevine，
 * 本类复刻同一条路：
 *   manifest 重写（见 [StreamRewriterDataSource]）→ Widevine CDM 生成 challenge
 *   → POST acquireWebPlaybackLicense → license 回灌 CDM → 解密播放。
 *
 * 装配：AppGraph 启动时 [configure]；PlaybackService 建 MediaSourceFactory 时取
 * [drmSessionManager]。未配置时返回 DRM_UNSUPPORTED（加密流会走降级 preview，
 * 现有播放链路不受影响）。注意 Media3 1.11 的 [MediaDrmCallback] 返回
 * `Response` 包装（不是裸 ByteArray），装配入口在 DefaultMediaSourceFactory 上。
 */
@OptIn(UnstableApi::class)
object AppleWidevine {

    private const val LICENSE_URL =
        "https://play.itunes.apple.com/WebObjects/MZPlay.woa/wa/acquireWebPlaybackLicense"

    /** CDM 没给默认地址时的兜底：谷歌 Widevine 设备证书服务器 */
    private const val FALLBACK_PROVISION_URL =
        "https://www.googleapis.cn/certificateprovisioning/v1/devicecertificates/create" +
            "?key=AIzaSyB-5OLKTx2iU5JKOfoWgYFpA_f_wV8j80c"

    @Volatile private var auth: AuthStore? = null
    @Volatile private var tokens: DevTokenProvider? = null

    private val LICENSE_RE = Regex("\"license\"\\s*:\\s*\"([^\"]+)\"")

    /** AppGraph 启动时注入凭据读取入口 */
    fun configure(authStore: AuthStore, tokenProvider: DevTokenProvider) {
        auth = authStore
        tokens = tokenProvider
    }

    /** MediaSourceFactory 挂载用；未配置时给 DRM_UNSUPPORTED（不碰 preview 链路） */
    fun drmSessionManager(@Suppress("UNUSED_PARAMETER") context: Context): DrmSessionManager {
        val authStore = auth ?: return DrmSessionManager.DRM_UNSUPPORTED
        val tokenProvider = tokens ?: return DrmSessionManager.DRM_UNSUPPORTED
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(AppleDrmCallback(authStore, tokenProvider))
    }

    private class AppleDrmCallback(
        private val auth: AuthStore,
        private val tokens: DevTokenProvider
    ) : MediaDrmCallback {

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest
        ): MediaDrmCallback.Response {
            // 完全照抄 Media3 1.11.1 HttpMediaDrmCallback（本机 javap 字节码逆向）：
            //   body = {"signedRequest":" + <原始请求字节> + "}   （JSON 包装，非查询参数）
            //   POST request.defaultUrl，Content-Type: application/json
            //   响应体原样返回给 CDM（1.11 官方不做任何 JSON/base64 剥离——
            //   exoplayer classes.jar 里全库无 "signedResponse" 字符串）
            val url = request.defaultUrl.takeIf { it.isNotBlank() } ?: FALLBACK_PROVISION_URL
            val body =
                "{\"signedRequest\":\"".toByteArray() + request.data + "\"}".toByteArray()
            YunoLog.d("DRM", "provision body ${body.size}B (json signedRequest) -> $url")
            val resp = runBlocking {
                Http.postBytes(url, body, mapOf("Content-Type" to "application/json"))
            }
            YunoLog.d("DRM", "provision response ${resp.size}B")
            return MediaDrmCallback.Response(resp)
        }

        override fun executeKeyRequest(
            uuid: UUID,
            request: ExoMediaDrm.KeyRequest
        ): MediaDrmCallback.Response {
            val adamId = WidevineContext.adamId()
            val initData = WidevineContext.initDataUri()
            YunoLog.d(
                "DRM",
                "license request: adamId=$adamId challenge=${request.data.size}B " +
                    "uri=${initData.take(64)}"
            )
            if (initData.isBlank()) {
                throw IllegalStateException("no initData captured from manifest")
            }
            val license = runBlocking {
                // Apple 的 license 端点吃 JSON（challenge/uri 都是 base64，无转义风险，手工拼）
                val body = buildString {
                    append("{\"challenge\":\"")
                        .append(Base64.encodeToString(request.data, Base64.NO_WRAP))
                        .append("\",")
                    append("\"key-system\":\"com.widevine.alpha\",")
                    append("\"uri\":\"").append(initData).append("\",")
                    if (adamId.isNotBlank()) {
                        append("\"adamId\":\"").append(adamId).append("\",")
                    }
                    // 与 Chrome/开源实现一致为 false（资料库曲目也用 false；真机 1.0.15/16 用 true 一样被拒，
                    // 但保持与抓包外的成功实现一致更稳妥）
                    append("\"isLibrary\":false,\"user-initiated\":true}")
                }

                // 头部按 Chrome 抓包（真机 2026-10-19 web 播放器实证）一比一复刻：
                //   x-apple-music-user-token（不是官方 API 的 Music-User-Token！）
                //   x-apple-renewal: true / accept: application/json / origin 同源
                val headers = LinkedHashMap<String, String>()
                headers["Origin"] = "https://music.apple.com"
                headers["Accept"] = "application/json"
                headers["X-Apple-Renewal"] = "true"
                val userToken = auth.cached.mediaUserToken
                if (userToken.isNotBlank()) headers["x-apple-music-user-token"] = userToken
                tokens.developerToken()?.let { headers["Authorization"] = "Bearer $it" }

                val resp = Http.postJson(LICENSE_URL, body, headers)
                YunoLog.d("DRM", "license response head: ${resp.take(160)}")

                val licenseB64 = LICENSE_RE.find(resp)?.groupValues?.get(1)
                if (licenseB64.isNullOrBlank()) {
                    throw IllegalStateException("no license field in response: ${resp.take(120)}")
                }
                Base64.decode(licenseB64, Base64.DEFAULT)
            }
            return MediaDrmCallback.Response(license)
        }
    }
}
