package com.xiaoliu.yuno.feature.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.ui.component.AlbumCard
import com.xiaoliu.yuno.core.ui.component.ArtworkImage
import com.xiaoliu.yuno.core.ui.component.CollapsingHeader
import com.xiaoliu.yuno.core.ui.component.GlassChip
import com.xiaoliu.yuno.core.ui.component.GlassSurface
import com.xiaoliu.yuno.core.ui.component.TrackCard
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary

// ============================================================================
// 主页（For You / 最近播放）—— Material 3 质感：
//   · 折叠大标题（M3 LargeTopAppBar 式滚动收缩）
//   · 封面圆角卡 + 细描边 + 阴影；无任何纯色块
// ============================================================================

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onAlbumClick: (Album) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    // M3 大标题折叠：列表滚动前 ~300px 内，标题 34sp → 21sp
    val collapseFraction = remember {
        derivedStateOf {
            (listState.firstVisibleItemScrollOffset +
                if (listState.firstVisibleItemIndex > 0) 420 else 0) / 300f
        }
    }

    Column(modifier.fillMaxSize()) {
        HomeHeader(scrollProvider = { collapseFraction.value })

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // 底部留白：列表内容能从 Liquid Glass 底栏 + 迷你播放器下面穿过去
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            if (state.loading && state.sections.isEmpty()) {
                item(key = "loading") { HomeLoading() }
            }

            state.error?.takeIf { state.sections.isEmpty() }?.let { msg ->
                item(key = "error") { HomeError(msg, onRetry = vm::refresh) }
            }

            state.sections.forEach { section ->
                when (section) {
                    is HomeSection.RecentPlays -> item(key = "shelf-${section.title}") {
                        RecentPlaysShelf(section, vm)
                    }

                    is HomeSection.AlbumsShelf -> item(key = "shelf-${section.title}") {
                        AlbumsShelfSection(section, onAlbumClick)
                    }

                    is HomeSection.PlaylistsShelf -> item(key = "shelf-${section.title}") {
                        PlaylistsShelfSection(section)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(scrollProvider: () -> Float) {
    // 干净的大标题，无问候语/提示语（真机反馈钦定）
    CollapsingHeader(
        title = "主页",
        collapseFractionProvider = scrollProvider,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
    )
}

/** 最近播放：横向歌曲卡（点击以整个货架为队列起播） */
@Composable
private fun RecentPlaysShelf(
    section: HomeSection.RecentPlays,
    vm: HomeViewModel
) {
    Column {
        SectionTitle(section.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(section.tracks, key = { it.id }) { track ->
                TrackCard(
                    track = track,
                    onClick = { vm.playTrack(track, section.tracks) },
                    modifier = Modifier.width(140.dp),
                    coverSize = 140.dp
                )
            }
        }
    }
}

/** 为你推荐：两列专辑网格（玻璃质感卡片） */
@Composable
private fun AlbumsShelfSection(
    section: HomeSection.AlbumsShelf,
    onAlbumClick: (Album) -> Unit
) {
    Column {
        SectionTitle(section.title)
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            section.albums.chunked(2).forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
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
}

/** 推荐歌单：横向封面卡 */
@Composable
private fun PlaylistsShelfSection(section: HomeSection.PlaylistsShelf) {
    Column {
        SectionTitle(section.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(section.playlists, key = { it.id }) { playlist ->
                PlaylistCard(playlist)
            }
        }
    }
}

@Composable
private fun PlaylistCard(playlist: Playlist) {
    Column(Modifier.width(150.dp)) {
        ArtworkImage(
            url = playlist.artworkUrl,
            modifier = Modifier
                .width(150.dp)
                .height(150.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            color = Color.White,
            fontSize = MaterialTheme.typography.titleSmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Text(
            text = playlist.curator.ifBlank { "编辑推荐" },
            color = YunoTextSecondary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1
        )
    }
}

// ---------------- 加载 / 出错 ----------------

@Composable
private fun HomeLoading() {
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(2) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                shadowElevation = 0.dp
            ) { }
        }
    }
}

@Composable
private fun HomeError(message: String, onRetry: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            GlassChip(text = "重试", onClick = onRetry)
        }
    }
}
