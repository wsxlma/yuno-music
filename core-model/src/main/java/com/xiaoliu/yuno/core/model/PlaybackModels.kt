package com.xiaoliu.yuno.core.model

enum class RepeatMode { OFF, ONE, ALL }

/** 对应 ExoPlayer 的 STATE_IDLE / STATE_BUFFERING / STATE_READY / STATE_ENDED */
enum class BufferState { IDLE, BUFFERING, READY, ENDED }

/**
 * 播放状态唯一事实源。
 *
 * 【架构铁律】全 App 只有 PlaybackService 会写入这份状态，
 * 迷你播放器、全屏页、歌词页、通知全部只读这一个流 ——
 * 「播放状态只有一个源头」。
 */
data class PlaybackState(
    val currentTrack: Track? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = -1
) {
    val hasTrack: Boolean get() = currentTrack != null

    /** 队列里还有下一首 */
    val hasNext: Boolean
        get() = currentTrack != null &&
            (shuffleEnabled || queueIndex < queue.lastIndex)

    val hasPrev: Boolean
        get() = currentTrack != null &&
            (shuffleEnabled || queueIndex > 0)
}
