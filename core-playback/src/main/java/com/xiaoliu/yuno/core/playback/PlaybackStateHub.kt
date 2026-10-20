package com.xiaoliu.yuno.core.playback

import com.xiaoliu.yuno.core.model.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 播放状态中枢 —— 「播放状态只有一个源头」的物理落点。
 *
 * 写入方：只有 PlaybackService（进程内单例，ExoPlayer 归它管）。
 * 读取方：迷你播放器 / 全屏播放页 / 歌词页 / 通知，全部 collect 这一个 StateFlow。
 * 旋屏、页面重建都不影响它 —— 因为它不挂在任何 Activity 上。
 */
object PlaybackStateHub {

    private val _state = MutableStateFlow(PlaybackState())

    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    val current: PlaybackState get() = _state.value

    /** 仅限 PlaybackService 调用 */
    internal fun update(transform: (PlaybackState) -> PlaybackState) {
        _state.update(transform)
    }

    /** 供测试/登出清理 */
    fun reset() {
        _state.value = PlaybackState()
    }
}
