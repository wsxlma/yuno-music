package com.xiaoliu.yuno.core.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.xiaoliu.yuno.core.model.RepeatMode
import com.xiaoliu.yuno.core.model.Track

// Track（我们的数据模型） ↔ MediaItem（ExoPlayer 的队列元素）互转。
// 全部元数据都塞进 MediaMetadata —— 系统媒体通知、锁屏、蓝牙显示的就是这些。

internal fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(streamUrl)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUrl.takeIf { it.isNotBlank() }?.toUri())
            .setDurationMs(durationMs)
            .build()
    )
    .build()

internal fun MediaItem.toTrack(): Track = Track(
    id = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    artist = mediaMetadata.artist?.toString().orEmpty(),
    album = mediaMetadata.albumTitle?.toString().orEmpty(),
    artworkUrl = mediaMetadata.artworkUri?.toString().orEmpty(),
    durationMs = mediaMetadata.durationMs ?: 0L,
    // 注意：同进程内 media3 会剥掉 localConfiguration，
    // 队列里取回的 streamUrl 可能为空 —— 反正队列展示不需要它
    streamUrl = runCatching { localConfiguration?.uri?.toString().orEmpty() }.getOrDefault("")
)

internal fun Int.toYunoRepeat(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}
