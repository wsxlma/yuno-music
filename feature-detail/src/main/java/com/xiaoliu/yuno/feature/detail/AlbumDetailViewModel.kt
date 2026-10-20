package com.xiaoliu.yuno.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.AlbumDetail
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.CatalogRepository
import com.xiaoliu.yuno.data.repository.DetailCache
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LocalCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AlbumDetailUiState(
    val loading: Boolean = true,
    val detail: AlbumDetail? = null,
    val error: String? = null,
    val addedToLibrary: Boolean = false
)

class AlbumDetailViewModel(
    private val albumId: String,
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val cache: LocalCache,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumDetailUiState())
    val state: StateFlow<AlbumDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** cache-first：退出再进秒开（磁盘缓存先上屏，后台静默刷新） */
    fun load() {
        viewModelScope.launch {
            val cached = runCatching { cache.read("album_$albumId") }.getOrNull()
                ?.let { runCatching { DetailCache.parseAlbum(it) }.getOrNull() }
            if (cached != null) {
                _state.update { it.copy(loading = false, detail = cached) }
            } else {
                _state.update { it.copy(loading = true) }
            }
            _state.update { it.copy(error = null) }

            runCatching { catalog.albumDetail(albumId) }
                .onSuccess { d ->
                    if (d != null) {
                        cache.write("album_$albumId", DetailCache.serializeAlbum(d))
                    }
                    _state.update {
                        it.copy(
                            loading = false,
                            detail = d ?: it.detail,
                            error = if (d == null && it.detail == null)
                                "专辑加载失败（id=$albumId）" else null
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

    /** 播放全部；shuffle = true 时打乱顺序（入口按钮区分） */
    fun playAll(shuffle: Boolean = false) {
        viewModelScope.launch {
            val tracks = _state.value.detail?.tracks ?: return@launch
            if (tracks.isEmpty()) return@launch
            val queue = if (shuffle) tracks.shuffled() else tracks
            controller.playQueue(queue, 0)
        }
    }

    fun playTrack(track: Track) {
        viewModelScope.launch {
            val tracks = _state.value.detail?.tracks ?: return@launch
            val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.playQueue(tracks, index)
        }
    }

    /** 加入待播列表（行长按菜单；官方 addMediaItem 路径，主线程转发） */
    fun addToQueue(track: Track) = controller.addToQueue(track)

    /** 插播到下一首 */
    fun playNext(track: Track) = controller.playNext(track)

    /** 加入资料库（Apple 账号的资料库写接口；个人使用，失败也不阻断 UI） */
    fun addToLibrary() {
        viewModelScope.launch {
            val album = _state.value.detail?.album ?: return@launch
            val ok = library.addAlbumToAppleLibrary(album.id)
            _state.update { it.copy(addedToLibrary = ok || it.addedToLibrary) }
        }
    }
}
