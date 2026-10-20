package com.xiaoliu.yuno.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.SearchResults
import com.xiaoliu.yuno.core.model.SongChart
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.CatalogRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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

/** 榜单地区：code = Apple storefront；null = 跟着账号走 */
data class ChartRegion(val code: String?, val label: String)

/** 榜单地区表（charts 端点的 storefront 是路径参数，任一地区都能查） */
val CHART_REGIONS: List<ChartRegion> = listOf(
    ChartRegion(null, "我的地区"),
    ChartRegion("cn", "中国"),
    ChartRegion("us", "美国"),
    ChartRegion("jp", "日本"),
    ChartRegion("kr", "韩国"),
    ChartRegion("tw", "台湾"),
    ChartRegion("hk", "香港"),
    ChartRegion("gb", "英国"),
    ChartRegion("fr", "法国"),
    ChartRegion("de", "德国"),
    ChartRegion("it", "意大利"),
    ChartRegion("es", "西班牙"),
    ChartRegion("ca", "加拿大"),
    ChartRegion("au", "澳大利亚"),
    ChartRegion("br", "巴西"),
    ChartRegion("mx", "墨西哥"),
    ChartRegion("in", "印度"),
    ChartRegion("sg", "新加坡"),
    ChartRegion("my", "马来西亚"),
    ChartRegion("th", "泰国"),
    ChartRegion("vn", "越南"),
    ChartRegion("id", "印尼"),
    ChartRegion("ph", "菲律宾"),
    ChartRegion("ru", "俄罗斯"),
    ChartRegion("tr", "土耳其"),
    ChartRegion("nl", "荷兰"),
    ChartRegion("se", "瑞典"),
    ChartRegion("pl", "波兰"),
    ChartRegion("sa", "沙特"),
    ChartRegion("ae", "阿联酋"),
    ChartRegion("za", "南非"),
    ChartRegion("ng", "尼日利亚"),
    ChartRegion("eg", "埃及")
)

data class ChartUiState(
    val loading: Boolean = true,
    val region: ChartRegion = CHART_REGIONS.first(),
    val chart: SongChart? = null,
    val error: String? = null
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val catalog: CatalogRepository,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _chart = MutableStateFlow(ChartUiState())
    val chart: StateFlow<ChartUiState> = _chart.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private var chartJob: Job? = null

    init {
        loadChart()
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

    /** 切榜单地区 —— 取消上一次请求，避免快速连点后旧响应盖掉新响应 */
    fun selectRegion(region: ChartRegion) {
        if (region == _chart.value.region) return
        _chart.update { it.copy(region = region) }
        loadChart()
    }

    fun refreshChart() = loadChart()

    private fun loadChart() {
        val region = _chart.value.region
        chartJob?.cancel()
        _chart.update { it.copy(loading = true, error = null) }
        chartJob = viewModelScope.launch {
            // 显式 try/catch 而不是 runCatching：runCatching 会把取消也当失败吞掉，
            // 连点地区时旧请求的取消会被写成"加载失败"，盖掉新请求的结果。
            val result = try {
                Result.success(catalog.songChart(storefront = region.code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.fold(
                onSuccess = { c ->
                    _chart.update {
                        it.copy(
                            loading = false,
                            chart = c.takeIf { ch -> ch.tracks.isNotEmpty() },
                            error = if (c.tracks.isEmpty()) "该地区暂无榜单" else null
                        )
                    }
                },
                onFailure = { e ->
                    _chart.update {
                        it.copy(loading = false, chart = null, error = e.message ?: "榜单加载失败")
                    }
                }
            )
        }
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
