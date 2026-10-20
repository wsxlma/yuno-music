package com.xiaoliu.yuno.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.SearchResults
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.CatalogRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SearchTab(val label: String) {
    SONGS("歌曲"),
    ALBUMS("专辑"),
    ARTISTS("歌手")
}

data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val results: SearchResults = SearchResults(),
    val tab: SearchTab = SearchTab.SONGS,
    val error: String? = null
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val catalog: CatalogRepository,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(350)                 // 输入防抖，别把 Apple 的接口打爆
                .distinctUntilChanged()
                .collectLatest { q ->
                    if (q.isBlank()) {
                        _state.update { it.copy(searching = false, results = SearchResults(), error = null) }
                        return@collectLatest
                    }
                    _state.update { it.copy(searching = true, error = null) }
                    runCatching { catalog.search(q) }
                        .onSuccess { r ->
                            _state.update { it.copy(searching = false, results = r) }
                        }
                        .onFailure { e ->
                            _state.update { it.copy(searching = false, error = e.message ?: "搜索失败") }
                        }
                }
        }
    }

    fun onQueryChange(q: String) {
        _state.update { it.copy(query = q) }
        queryFlow.value = q
    }

    fun selectTab(tab: SearchTab) {
        _state.update { it.copy(tab = tab) }
    }

    /** 点搜索结果里的歌：以结果列表为队列起播 */
    fun play(track: Track, queue: List<Track>) {
        viewModelScope.launch {
            val q = queue.ifEmpty { listOf(track) }
            val index = q.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.playQueue(q, index)
        }
    }

    /** 加入待播列表（行长按菜单；官方 addMediaItem 路径，主线程转发） */
    fun addToQueue(track: Track) = controller.addToQueue(track)

    /** 插播到下一首 */
    fun playNext(track: Track) = controller.playNext(track)
}
