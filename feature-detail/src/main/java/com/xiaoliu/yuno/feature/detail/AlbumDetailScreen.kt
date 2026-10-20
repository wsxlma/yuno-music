package com.xiaoliu.yuno.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.ui.component.ArtworkImage
import com.xiaoliu.yuno.core.ui.component.GlassIconButton
import com.xiaoliu.yuno.core.ui.component.GlassSurface
import com.xiaoliu.yuno.core.ui.component.TrackRow
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoInk
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// ============================================================================
// 专辑详情 —— Material 3 质感：
//   · 大封面头图 + 下方毛玻璃信息卡（需求钦定布局）
//   · 背景用封面 dominant 色压暗打底，不用纯色 surface
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
fun AlbumDetailScreen(
    vm: AlbumDetailViewModel,
    onBack: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val detail = state.detail
    val palette = rememberArtworkPalette(detail?.album?.artworkUrl)

    Box(
        modifier
            .fillMaxSize()
            .hazeSource(hazeState)
    ) {
        // 氛围底：封面主色 → 黑（比歌词页的渐变克制得多，没有光斑漂移）
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(palette.dominant.copy(alpha = 0.38f), YunoInk)
                    )
                )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            // 底部留出迷你播放器的高度（64dp 胶囊 + 间距 + 导航栏），
            // 否则最后一首歌会被控制器永久挡住、点不到
            contentPadding = PaddingValues(bottom = 140.dp)
        ) {
            item(key = "back") { BackRow(onBack, hazeState) }

            item(key = "head") {
                HeadSection(
                    artworkUrl = detail?.album?.artworkUrl.orEmpty(),
                    title = detail?.album?.title.orEmpty(),
                    artist = detail?.album?.artist.orEmpty(),
                    meta = buildMeta(state),
                    hazeState = hazeState
                )
            }

            item(key = "actions") { ActionRow(vm = vm, hazeState = hazeState) }

            when {
                state.loading -> item(key = "loading") { AlbumLoading() }
                state.error != null -> item(key = "error") { HintText(state.error.orEmpty()) }
                detail != null -> itemsIndexed(
                    items = detail.tracks,
                    key = { _, t -> t.id }
                ) { index, track ->
                    TrackRow(
                        track = track,
                        onClick = { vm.playTrack(track) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        leadingIndex = index + 1,
                        leadingArtwork = false,
                        trailingText = formatDuration(track.durationMs),
                        onAddToQueue = { vm.addToQueue(it) },
                        onPlayNext = { vm.playNext(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit, hazeState: HazeState) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 14.dp, top = 12.dp)
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onBack,
            hazeState = hazeState
        )
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun HeadSection(
    artworkUrl: String,
    title: String,
    artist: String,
    meta: String,
    hazeState: HazeState
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArtworkImage(
            url = artworkUrl,
            modifier = Modifier
                .size(204.dp)
                .shadow(
                    elevation = 26.dp,
                    shape = RoundedCornerShape(26.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.5f),
                    spotColor = Color.Black.copy(alpha = 0.6f)
                ),
            shape = RoundedCornerShape(26.dp)
        )
        // 间距拉开（真机反馈：封面与描述太紧凑）
        Spacer(Modifier.height(30.dp))

        // 液态玻璃信息卡（换真玻璃，告别"塑料玻璃"）
        val cardShape = RoundedCornerShape(26.dp)
        val cardStyle = rememberLiquidGlassStyle(shape = cardShape)
        Column(
            Modifier
                .fillMaxWidth()
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = cardStyle
                )
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = MaterialTheme.typography.headlineSmall.fontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = artist,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = meta,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ActionRow(vm: AlbumDetailViewModel, hazeState: HazeState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PillAction(
            text = "播放全部",
            icon = Icons.Rounded.PlayArrow,
            onClick = { vm.playAll(shuffle = false) },
            hazeState = hazeState,
            modifier = Modifier.weight(1f)
        )
        PillAction(
            text = "随机播放",
            icon = Icons.Rounded.Shuffle,
            onClick = { vm.playAll(shuffle = true) },
            hazeState = hazeState,
            modifier = Modifier.weight(1f)
        )
        GlassIconButton(
            icon = Icons.Rounded.Add,
            onClick = { vm.addToLibrary() },
            size = 46.dp,
            hazeState = hazeState
        )
    }
}

/** 胶囊按钮 —— 真·液态玻璃版（真机反馈：塑料玻璃换掉） */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun PillAction(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    val style = rememberLiquidGlassStyle(shape = shape)
    Row(
        modifier = modifier
            .height(46.dp)
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = style
            )
            .clip(shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            color = Color.White,
            fontSize = MaterialTheme.typography.labelLarge.fontSize,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun buildMeta(state: AlbumDetailUiState): String {
    val album = state.detail?.album ?: return ""
    val year = album.releaseDate.take(4)
    return listOfNotNull(
        year.takeIf { it.length == 4 },
        album.trackCount.takeIf { it > 0 }?.let { "$it 首" },
        album.genreName.takeIf { it.isNotBlank() }
    ).joinToString(" · ")
}

@Composable
private fun AlbumLoading() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        YunoLoadingCircle()
        Text(
            text = "正在加载…",
            color = YunoTextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun HintText(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
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
