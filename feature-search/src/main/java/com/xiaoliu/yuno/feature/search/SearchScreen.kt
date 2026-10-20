package com.xiaoliu.yuno.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.AlbumCard
import com.xiaoliu.yuno.core.ui.component.ArtistRow
import com.xiaoliu.yuno.core.ui.component.CollapsingHeader
import com.xiaoliu.yuno.core.ui.component.GlassChip
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import com.xiaoliu.yuno.core.ui.component.GlassSurface
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.component.TrackRow
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions

// ============================================================================
// 搜索页 —— Material 3 质感：
//   · 输入框 OutlinedTextField 背景透明化、边框半透明（需求钦定）
//   · 空查询时展示各地区歌曲榜（Top 25），可切地区
//   · 结果按 歌曲 / 专辑 / 歌手 三个 Tab 切换
// ============================================================================

@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onAlbumClick: (Album) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val chartState by vm.chart.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // 结果列表滚动时大标题折叠（M3 LargeTopAppBar 式）
    val collapseFraction = remember {
        derivedStateOf {
            (listState.firstVisibleItemScrollOffset +
                if (listState.firstVisibleItemIndex > 0) 420 else 0) / 300f
        }
    }

    Column(modifier.fillMaxSize()) {
        CollapsingHeader(
            title = "搜索",
            collapseFractionProvider = { collapseFraction.value },
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        // 真机反馈 Beta6：标题「搜索」和搜索框挨得太近、且框没边框显得黑不溜秋
        // → 拉开间距 + 搜索框本体加半透明描边
        Spacer(Modifier.height(10.dp))

        SearchField(
            query = state.query,
            onQueryChange = vm::onQueryChange,
            hazeState = hazeState,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(16.dp))

        when {
            // 空查询 → 各地区歌曲榜（Top 25）。榜单和结果列表共用 listState，
            // 大标题的折叠才跟得上滚动。
            state.query.isBlank() -> TopChartBoard(
                chartState = chartState,
                onSelectRegion = vm::selectRegion,
                onPlay = { track -> vm.play(track, chartState.chart?.tracks.orEmpty()) },
                onAddToQueue = vm::addToQueue,
                onPlayNext = vm::playNext,
                hazeState = hazeState,
                listState = listState
            )

            state.searching && state.results.isEmpty -> SearchingBlock()

            state.error != null && state.results.isEmpty -> HintText(state.error.orEmpty())

            state.results.isEmpty -> HintText("没有找到「${state.query}」相关内容")

            else -> {
                ResultTabs(
                    active = state.tab,
                    onSelect = vm::selectTab,
                    hazeState = hazeState,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(6.dp))
                ResultList(state = state, vm = vm, onAlbumClick = onAlbumClick, listState = listState)
            }
        }
    }
}

/** 搜索框：装进液态玻璃胶囊（真机反馈：搜索框也上玻璃），输入框本体全透明 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(18.dp)
    val glassStyle = rememberLiquidGlassStyle(shape = shape)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = glassStyle
            )
            .clip(shape)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("歌曲、专辑、歌手", color = YunoTextSecondary) },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    tint = YunoTextSecondary
                )
            },
            singleLine = true,
            shape = shape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            // 真机反馈 Beta6：旧版边框全 Transparent，导致搜索框「黑不溜秋」
            // → 加半透明描边（聚焦时更亮），框体立刻立起来
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedBorderColor = Color.White.copy(alpha = 0.55f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
                focusedLabelColor = YunoAccent,
                cursorColor = YunoAccent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            )
        )
    }
}

/**
 * 各地区歌曲榜（Top 25）。
 *
 * Apple 的 charts 只有 mostPlayed / dailyGlobalTop / cityTop 三种 kind，没有「月度」；
 * 其中只有 mostPlayed 按 storefront 出，所以「每个地区」= 切 storefront。
 * 榜名用 Apple 返回的原文，不自己编标题。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun TopChartBoard(
    chartState: ChartUiState,
    onSelectRegion: (ChartRegion) -> Unit,
    onPlay: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit,
    hazeState: HazeState,
    listState: LazyListState
) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 180.dp)) {
        item(key = "regions") {
            RegionStrip(
                regions = CHART_REGIONS,
                selected = chartState.region,
                onSelect = onSelectRegion,
                hazeState = hazeState
            )
        }

        item(key = "chart-title") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = chartState.chart?.name?.ifBlank { null } ?: "歌曲榜",
                    color = Color.White,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.Bold
                )
                if (chartState.chart != null) {
                    Text(
                        text = "共 ${chartState.chart.tracks.size} 首",
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        when {
            chartState.loading -> item(key = "chart-loading") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    YunoLoadingCircle()
                }
            }

            chartState.error != null -> item(key = "chart-error") {
                HintText(chartState.error.orEmpty())
            }

            else -> itemsIndexed(
                items = chartState.chart?.tracks.orEmpty(),
                key = { _, t -> "c-${t.id}" }
            ) { index, track ->
                TrackRow(
                    track = track,
                    onClick = { onPlay(track) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                    leadingIndex = index + 1,
                    leadingArtwork = true,
                    trailingText = formatDuration(track.durationMs),
                    onAddToQueue = onAddToQueue,
                    onPlayNext = onPlayNext
                )
            }
        }
    }
}

/** 地区切换条：横向滚动的玻璃胶囊 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun RegionStrip(
    regions: List<ChartRegion>,
    selected: ChartRegion,
    onSelect: (ChartRegion) -> Unit,
    hazeState: HazeState
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(regions, key = { it.label }) { region ->
            GlassChip(
                text = region.label,
                selected = region == selected,
                onClick = { onSelect(region) },
                hazeState = hazeState
            )
        }
    }
}

/** 结果 Tab：三枚液态玻璃胶囊 */
@Composable
private fun ResultTabs(
    active: SearchTab,
    onSelect: (SearchTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SearchTab.entries.forEach { tab ->
            GlassChip(
                text = tab.label,
                selected = tab == active,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
                hazeState = hazeState
            )
        }
    }
}

@Composable
private fun ResultList(
    state: SearchUiState,
    vm: SearchViewModel,
    onAlbumClick: (Album) -> Unit,
    listState: LazyListState
) {
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 180.dp)) {
        when (state.tab) {
            SearchTab.SONGS -> {
                items(state.results.tracks, key = { "t-${it.id}" }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { vm.play(track, state.results.tracks) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        leadingArtwork = true,
                        trailingText = formatDuration(track.durationMs),
                        onAddToQueue = { vm.addToQueue(it) },
                        onPlayNext = { vm.playNext(it) }
                    )
                }
            }

            SearchTab.ALBUMS -> {
                state.results.albums.chunked(2).forEachIndexed { rowIdx, row ->
                    item(key = "ar-$rowIdx") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            row.forEach { album ->
                                AlbumCard(
                                    album = album,
                                    onClick = { onAlbumClick(album) },
                                    coverSize = 150.dp,
                                    modifier = Modifier.width(150.dp)
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.width(150.dp))
                        }
                    }
                }
            }

            SearchTab.ARTISTS -> {
                items(state.results.artists, key = { "a-${it.id}" }) { artist ->
                    ArtistRow(
                        artist = artist,
                        onClick = { vm.onQueryChange(artist.name) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchingBlock() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        YunoLoadingCircle()
        Text(
            text = "正在搜索…",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HintText(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = YunoTextSecondary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
