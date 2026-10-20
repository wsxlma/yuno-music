package com.xiaoliu.yuno.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.CatalogRepository
import com.xiaoliu.yuno.data.repository.HomeCache
import com.xiaoliu.yuno.data.repository.LocalCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val loading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val catalog: CatalogRepository,
    private val cache: LocalCache,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * cache-first（真机需求：不要每次进 App 都从零加载）：
     *   ① 磁盘缓存立即上屏（秒开、无 loading）
     *   ② 后台刷新网络结果 → 回写缓存 → 替换展示；失败且无缓存兜底才报错
     */
    fun refresh() {
        viewModelScope.launch {
            val cached = runCatching { cache.read("home") }.getOrNull()
                ?.let { runCatching { HomeCache.parse(it) }.getOrNull() }
            if (!cached.isNullOrEmpty()) {
                _state.update { it.copy(loading = false, sections = cached) }
            } else {
                _state.update { it.copy(loading = true) }
            }
            _state.update { it.copy(error = null) }

            runCatching { catalog.homeSections() }
                .onSuccess { sections ->
                    cache.write("home", HomeCache.serialize(sections))
                    _state.update { it.copy(loading = false, sections = sections, error = null) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            loading = false,
                            // 有缓存兜底时不打断展示，只在没内容时才提示
                            error = if (it.sections.isEmpty())
                                (e.message ?: "加载失败，检查网络或登录状态")
                            else null
                        )
                    }
                }
        }
    }

    /** 点某首歌 → 以所在货架为队列开播 */
    fun playTrack(track: Track, queue: List<Track>) {
        viewModelScope.launch {
            val q = queue.ifEmpty { listOf(track) }
            val index = q.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.playQueue(q, index)
        }
    }

    /** 点专辑卡 → 拉曲目整张播放 */
    fun playAlbum(album: Album) {
        viewModelScope.launch {
            catalog.albumDetail(album.id)?.let { detail ->
                controller.playQueue(detail.tracks, 0)
            }
        }
    }
}
