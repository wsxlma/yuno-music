package com.xiaoliu.yuno.data.playback

import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.core.network.api.AppleMusicApi
import com.xiaoliu.yuno.core.network.api.ApplePlaybackApi
import com.xiaoliu.yuno.core.playback.StreamResolver

/**
 * Apple 流解析器 —— StreamResolver 的生产实现。
 *
 * 策略（调研钦定）：
 *  1. webPlayback lease 拿全曲 HLS（AAC 256）
 *  2. 拿不到（3076/2002/超时/未登录）→ 30 秒 preview（未加密 m4a，必能播）
 *  3. 全曲 HLS 是 Widevine 加密的：ExoPlayer 解不了时由
 *     PlaybackController.onPlayerError → resolveFallback() 再降 preview
 */
class AppleStreamResolver(
    private val playbackApi: ApplePlaybackApi,
    private val catalogApi: AppleMusicApi
) : StreamResolver {

    override suspend fun resolve(track: Track): Track? {
        if (track.streamUrl.isNotBlank()) return track

        runCatching { playbackApi.fetchStreamUrl(track.id) }.getOrNull()
            ?.let {
                YunoLog.d("Resolver", "full-track lease OK: ${track.title} (${track.id})")
                return track.copy(streamUrl = it)
            }

        return resolveFallback(track)
    }

    override suspend fun resolveFallback(track: Track): Track? {
        val preview = runCatching { catalogApi.songPreviewUrl(track.id) }.getOrNull()
            ?: return null
        YunoLog.d("Resolver", "fallback preview: ${track.title} (${track.id})")
        return track.copy(streamUrl = preview)
    }
}
