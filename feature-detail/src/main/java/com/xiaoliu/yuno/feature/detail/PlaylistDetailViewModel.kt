package com.xiaoliu.yuno.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.DetailCache
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LocalCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistDetailUiState(
    val loading: Boolean = true,
    val detail: PlaylistDetail? = null,
    val error: String? = null
)

class PlaylistDetailViewModel(
    private val playlistId: String,
    private val library: LibraryRepository,
    private val cache: LocalCache,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistDetailUiState())
    val state: StateFlow<PlaylistDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** cache-first：退出再进秒开（磁盘缓存先上屏，后台静默刷新） */
    fun load() {
        viewModelScope.launch {
            val cached = runCatching { cache.read("playlist_$playlistId") }.getOrNull()
                ?.let { runCatching { DetailCache.parsePlaylist(it) }.getOrNull() }
            if (cached != null) {
                _state.update { it.copy(loading = false, detail = cached) }
            } else {
                _state.update { it.copy(loading = true) }
            }
            _state.update { it.copy(error = null) }

            runCatching { library.playlistDetail(playlistId) }
                .onSuccess { d ->
                    if (d != null) {
                        cache.write("playlist_$playlistId", DetailCache.serializePlaylist(d))
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            detail = d ?: it.detail,
                            error = if (d == null && it.detail == null)
                                "歌单加载失败（id=$playlistId）" else null
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = if (it.detail == null) (e.message ?: "加载失败") else null
                        )
                    }
                }
        }
    }

    fun playAll(shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = _state.value.detail?.tracks ?: return@launch
            if (tracks.isEmpty()) return@launch
            controller.playQueue(if (shuffle) tracks.shuffled() else tracks, 0)
        }
    }

    fun playTrack(track: Track) {
        viewModelScope.launch {
            val tracks = _state.value.detail?.tracks ?: return@launch
            controller.playQueue(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
        }
    }

    /** 加入待播列表（行长按菜单；官方 addMediaItem 路径，主线程转发） */
    fun addToQueue(track: Track) = controller.addToQueue(track)

    /** 插播到下一首 */
    fun playNext(track: Track) = controller.playNext(track)
}
