package com.xiaoliu.yuno.feature.coverflow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.PlaybackState
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 封面橱窗 VM —— 只转发播放动作，不持有状态（PlaybackStateHub 是唯一事实源）。
 * 播放逻辑（用户钦定）：
 *   - 拖到「正在播放」正中 → 立即播放这张（playAt）
 *   - 拖到后面 → 作为下一曲（playNextAt）
 */
class CoverFlowViewModel(
    private val controller: PlaybackController
) : ViewModel() {

    val playback: StateFlow<PlaybackState> = PlaybackStateHub.state

    /** 拖到正中：立即播放队列第 index 首 */
    fun playAt(index: Int) {
        val queue = playback.value.queue
        if (index !in queue.indices) return
        viewModelScope.launch { controller.playQueue(queue, index) }
    }

    /** 拖到后面：作为下一曲插播 */
    fun playNextAt(index: Int) {
        val track = playback.value.queue.getOrNull(index) ?: return
        controller.playNext(track)
    }

    fun togglePlayPause() = controller.playPause()

    fun next() = controller.next()

    fun previous() = controller.prev()
}