package com.xiaoliu.yuno.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.Lyrics
import com.xiaoliu.yuno.core.model.PlaybackState
import com.xiaoliu.yuno.core.model.RepeatMode
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import com.xiaoliu.yuno.data.repository.LyricsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 播放器 VM —— 不持有任何播放状态，只做两件事：
 *  1. 把 PlaybackStateHub 的单一状态流，拆成若干"窄流"给不同叶子组件订阅
 *     （播放进度 50ms 一条流，靠它是保证「只有当前行歌词重组」的关键）
 *  2. 把 UI 手势转发给 PlaybackController
 */
class PlayerViewModel(
    val controller: PlaybackController,
    private val lyricsRepository: LyricsRepository,
    private val favoriteStore: com.xiaoliu.yuno.data.repository.FavoriteStore
) : ViewModel() {

    /** 唯一事实源（只读） */
    val playback: StateFlow<PlaybackState> = PlaybackStateHub.state

    // ---------------- 窄流：谁需要谁订阅 ----------------

    val trackFlow: Flow<Track?> = playback
        .map { it.currentTrack }
        .distinctUntilChanged()

    /** 50ms 一条的高频进度流 —— 只允许进度条、迷你进度线、当前行歌词订阅它 */
    val positionMsFlow: Flow<Long> = playback
        .map { it.positionMs }
        .distinctUntilChanged()

    val durationMsFlow: Flow<Long> = playback
        .map { it.durationMs }
        .distinctUntilChanged()

    val isPlayingFlow: Flow<Boolean> = playback
        .map { it.isPlaying }
        .distinctUntilChanged()

    val shuffleFlow: Flow<Boolean> = playback
        .map { it.shuffleEnabled }
        .distinctUntilChanged()

    val repeatFlow: Flow<RepeatMode> = playback
        .map { it.repeatMode }
        .distinctUntilChanged()

    /** 播放队列（队列面板用） */
    val queueFlow: Flow<List<Track>> = playback
        .map { it.queue }
        .distinctUntilChanged()

    val queueIndexFlow: Flow<Int> = playback
        .map { it.queueIndex }
        .distinctUntilChanged()

    // ---------------- 歌词 ----------------

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private var lyricsJob: Job? = null

    init {
        viewModelScope.launch {
            trackFlow.collect { track ->
                if (track == null) {
                    _lyrics.value = null
                    _lyricsLoading.value = false
                } else {
                    loadLyrics(track)
                }
            }
        }
    }

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _lyricsLoading.value = true
            _lyrics.value = null
            // 内存缓存 → 文件缓存 → Apple TTML → LRCLIB(增强/LRC) → null
            _lyrics.value = lyricsRepository.getLyrics(track)
            _lyricsLoading.value = false
        }
    }

    // ---------------- 本地收藏（全屏页星标） ----------------

    val favoriteIds: Flow<Set<String>> = favoriteStore.ids

    fun toggleFavorite(trackId: String) {
        viewModelScope.launch { favoriteStore.toggle(trackId) }
    }

    // ---------------- 控制（转发给 PlaybackController，UI 主线程调用） ----------------

    fun togglePlayPause() = controller.playPause()

    fun next() = controller.next()

    fun previous() = controller.prev()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    /** 队列面板：点某首直接跳到它（队列里 streamUrl 已解析好，走快路径） */
    fun playAt(index: Int) {
        val queue = playback.value.queue
        if (index !in queue.indices) return
        viewModelScope.launch { controller.playQueue(queue, index) }
    }

    /** 队列拖动排序（播放队列面板） */
    fun moveInQueue(from: Int, to: Int) {
        viewModelScope.launch { controller.moveInQueue(from, to) }
    }

    /** 队列移出（播放队列面板） */
    fun removeFromQueue(index: Int) {
        viewModelScope.launch { controller.removeFromQueue(index) }
    }

    fun toggleShuffle() = controller.toggleShuffle()

    fun cycleRepeat() = controller.cycleRepeat()
}
