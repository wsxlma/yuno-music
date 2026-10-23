package com.xiaoliu.yuno.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.ArtworkImage
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary

/** TV 首页：沿用手机版货架数据（继续播放 / 为你推荐 / 歌单 / 热门），横排海报布局 */
@Composable
fun TvHomePage(vm: TvViewModel) {
    val state by vm.home.collectAsStateWithLifecycle()
    var menuTrack by remember { mutableStateOf<Track?>(null) }

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading && state.sections.isEmpty() -> TvCenter { YunoLoadingCircle(size = 42.dp) }

            state.error != null && state.sections.isEmpty() -> TvCenter {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error.orEmpty(),
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(20.dp))
                    TvActionChip("重试") { vm.loadHome() }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                state.sections.forEach { section ->
                    item(key = "h-${section.title}") { TvSectionTitle(section.title) }
                    item(key = "s-${section.title}") {
                        when (section) {
                            is HomeSection.RecentPlays -> TvShelf {
                                section.tracks.forEach { track ->
                                    TvPosterCard(
                                        artworkUrl = track.artworkUrl,
                                        title = track.title,
                                        subtitle = track.artist,
                                        onClick = { vm.playTrack(track, section.tracks) },
                                        onLongClick = { menuTrack = track },
                                        onMenu = { menuTrack = track }
                                    )
                                }
                            }

                            is HomeSection.AlbumsShelf -> TvShelf {
                                section.albums.forEach { album ->
                                    TvPosterCard(
                                        artworkUrl = album.artworkUrl,
                                        title = album.title,
                                        subtitle = album.artist,
                                        onClick = { vm.playAlbum(album) }
                                    )
                                }
                            }

                            is HomeSection.PlaylistsShelf -> TvShelf {
                                section.playlists.forEach { playlist ->
                                    TvPosterCard(
                                        artworkUrl = playlist.artworkUrl,
                                        title = playlist.name,
                                        subtitle = playlist.curator,
                                        onClick = { vm.playPlaylist(playlist) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    TvTrackMenu(
        track = menuTrack,
        onDismiss = { menuTrack = null },
        onAddToQueue = vm::addToQueue,
        onPlayNext = vm::playNext
    )
}

/** TV 资料库：专辑 + 歌单，流式网格自动换行 */
@Composable
fun TvLibraryPage(vm: TvViewModel) {
    val state by vm.libraryUi.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        when {
            state.loading && state.albums.isEmpty() && state.playlists.isEmpty() ->
                TvCenter { YunoLoadingCircle(size = 42.dp) }

            state.error != null -> TvCenter {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.error.orEmpty(),
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(20.dp))
                    TvActionChip("重试") { vm.loadLibrary() }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 12.dp, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "albums-title") { TvSectionTitle("专辑") }
                item(key = "albums-grid") {
                    TvFlowGrid(state.albums) { album ->
                        TvPosterCard(
                            artworkUrl = album.artworkUrl,
                            title = album.title,
                            subtitle = album.artist,
                            onClick = { vm.playAlbum(album) }
                        )
                    }
                }
                item(key = "playlists-title") { TvSectionTitle("歌单") }
                item(key = "playlists-grid") {
                    TvFlowGrid(state.playlists) { playlist ->
                        TvPosterCard(
                            artworkUrl = playlist.artworkUrl,
                            title = playlist.name,
                            subtitle = playlist.curator,
                            onClick = { vm.playPlaylist(playlist) }
                        )
                    }
                }
            }
        }
    }
}

// ---------------- 共用小件 ----------------

@Composable
private fun TvCenter(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun TvSectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}

@Composable
private fun TvShelf(content: @Composable () -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        item { content() }
    }
}

/** 简单流式网格：放不下的自动换行（资料库专辑/歌单） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TvFlowGrid(items: List<T>, slot: @Composable (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items.forEach { slot(it) }
    }
}

/** TV 海报卡：方形封面 + 标题 + 副标题；OK 播放、长按 OK / 菜单键呼出曲目菜单 */
@Composable
fun TvPosterCard(
    artworkUrl: String?,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .width(232.dp)
            .tvFocusable(
                onClick = onClick,
                onLongClick = onLongClick,
                onMenu = onMenu,
                shape = RoundedCornerShape(20.dp)
            )
            .padding(10.dp)
    ) {
        ArtworkImage(
            url = artworkUrl,
            modifier = Modifier
                .width(212.dp)
                .height(212.dp),
            shape = RoundedCornerShape(18.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                color = YunoTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TvActionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}