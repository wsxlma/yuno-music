package com.xiaoliu.yuno.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.network.AudioQuality
import com.xiaoliu.yuno.core.network.LyricsSourcePref
import com.xiaoliu.yuno.core.ui.component.LyricFont
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.AlbumCard
import com.xiaoliu.yuno.core.ui.component.ArtworkImage
import com.xiaoliu.yuno.core.ui.component.CollapsingHeader
import com.xiaoliu.yuno.core.ui.component.GlassChip
import com.xiaoliu.yuno.core.ui.component.GlassSurface
import com.xiaoliu.yuno.core.ui.component.TrackRow
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

// ============================================================================
// 资料库 —— 「像 Apple Music 但有心思」的布局：
//   · 折叠大标题 + 账号渐变徽章 + 订阅状态（M3 LargeTopAppBar 式）
//   · 「最近添加」横滑大卡（快速找回刚入库的东西）
//   · 筛选胶囊：全部 / 歌曲 / 专辑 / 播放列表 / 本地收藏
//   · 全部视图：专辑墙 + 歌曲流混合编排
//   · 歌曲行尾带心形 —— 本地收藏一键切换（DataStore）
// ============================================================================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LibraryScreen(
    vm: LibraryViewModel,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    hazeState: HazeState,
    onExportLogs: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 账户/关于弹层（右上徽章触发）
    var showAbout by remember { mutableStateOf(false) }
    // 音质偏好：AboutDialog 里切换，AudioQuality 单例持久化
    var audioQuality by remember { mutableStateOf(AudioQuality.quality) }
    var dolbyAtmos by remember { mutableStateOf(AudioQuality.dolbyAtmosEnabled) }
    // 歌词源偏好：AboutDialog 里切换，LyricsSourcePref 单例持久化
    var lyricsSourceMode by remember { mutableStateOf(LyricsSourcePref.mode) }
    // 歌词字体偏好：AboutDialog 里切换，LyricFont 单例持久化
    var lyricFontPreset by remember { mutableStateOf(LyricFont.preset) }
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    val listState = rememberLazyListState()
    // M3 大标题折叠
    val collapseFraction = remember {
        derivedStateOf {
            (listState.firstVisibleItemScrollOffset +
                if (listState.firstVisibleItemIndex > 0) 420 else 0) / 300f
        }
    }

    Column(modifier.fillMaxSize()) {
        LibraryHeader(
            accountName = state.accountName,
            subscriptionActive = state.subscription.active,
            subscriptionTier = state.subscription.tier,
            scrollProvider = { collapseFraction.value },
            hazeState = hazeState,
            onBadgeClick = { showAbout = true }
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            // 底部留白：列表内容能从 Liquid Glass 底栏 + 迷你播放器下面穿过去
            contentPadding = PaddingValues(bottom = 180.dp)
        ) {
            if (state.recentlyAdded.isNotEmpty()) {
                item(key = "recent") {
                    SectionLabel("最近添加")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(state.recentlyAdded, key = { "ra-${it.id}" }) { album ->
                            AlbumCard(
                                album = album,
                                onClick = { onAlbumClick(album) },
                                coverSize = 138.dp,
                                modifier = Modifier.width(138.dp)
                            )
                        }
                    }
                }
            }

            item(key = "filters") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(LibraryFilter.entries.toList(), key = { it.name }) { filter ->
                        GlassChip(
                            text = filter.label,
                            selected = state.filter == filter,
                            onClick = { vm.setFilter(filter) },
                            hazeState = hazeState
                        )
                    }
                }
            }

            if (state.loading) {
                item(key = "loading") { LibraryLoading() }
            }

            state.error?.takeIf { state.songs.isEmpty() && state.albums.isEmpty() }?.let { msg ->
                item(key = "error") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        GlassSurface {
                            Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(10.dp))
                            GlassChip(text = "重试", onClick = vm::load)
                        }
                    }
                }
            }

            when (state.filter) {
                LibraryFilter.ALL -> {
                    // 播放列表货架置顶（真机要求：全部页里播放列表放在专辑上面）
                    if (state.playlists.isNotEmpty()) {
                        item(key = "playlists-label") { SectionLabel("播放列表") }
                        item(key = "playlists-shelf") {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(state.playlists, key = { "ap-${it.id}" }) { playlist ->
                                    PlaylistTile(
                                        playlist = playlist,
                                        onClick = { onPlaylistClick(playlist) }
                                    )
                                }
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item(key = "albums-label") { SectionLabel("专辑") }
                        state.albums.chunked(2).forEachIndexed { rowIdx, row ->
                            item(key = "album-row-$rowIdx") {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 9.dp),
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
                    if (state.songs.isNotEmpty()) {
                        item(key = "songs-label") { SectionLabel("歌曲") }
                        itemsIndexed(
                            items = state.songs,
                            key = { _, t -> "s-${t.id}" }
                        ) { _, track ->
                            SongRowWithHeart(
                                track = track,
                                isFavorite = track.id in state.favorites,
                                onClick = { vm.playSong(track) },
                                onToggleFavorite = { vm.toggleFavorite(track.id) },
                                onAddToQueue = { vm.addToQueue(it) },
                                onPlayNext = { vm.playNext(it) }
                            )
                        }
                    }
                }

                LibraryFilter.SONGS -> itemsIndexed(
                    items = state.songs,
                    key = { _, t -> "s-${t.id}" }
                ) { _, track ->
                    SongRowWithHeart(
                        track = track,
                        isFavorite = track.id in state.favorites,
                        onClick = { vm.playSong(track) },
                        onToggleFavorite = { vm.toggleFavorite(track.id) },
                        onAddToQueue = { vm.addToQueue(it) },
                        onPlayNext = { vm.playNext(it) }
                    )
                }

                LibraryFilter.ALBUMS -> {
                    state.albums.chunked(2).forEachIndexed { rowIdx, row ->
                        item(key = "album-row-$rowIdx") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 9.dp),
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

                LibraryFilter.PLAYLISTS -> items(
                    items = state.playlists,
                    key = { "p-${it.id}" }
                ) { playlist ->
                    PlaylistRow(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                }

                LibraryFilter.FAVORITES -> {
                    val favs = state.favoriteSongs
                    if (favs.isEmpty()) {
                        // 空态不显示任何提示语
                        item(key = "fav-empty") { Spacer(Modifier.height(24.dp)) }
                    } else {
                        itemsIndexed(favs, key = { _, t -> "f-${t.id}" }) { _, track ->
                            SongRowWithHeart(
                                track = track,
                                isFavorite = true,
                                onClick = { vm.playSong(track) },
                                onToggleFavorite = { vm.toggleFavorite(track.id) },
                                onAddToQueue = { vm.addToQueue(it) },
                                onPlayNext = { vm.playNext(it) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAbout) {
        // 2.0.0 Beta2：设置面板重写为 Material 3（ModalBottomSheet + FilterChip + ListItem）
        SettingsSheet(
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            versionName = versionName,
            quality = audioQuality,
            onSelectQuality = { q ->
                audioQuality = q
                AudioQuality.setQuality(q)
            },
            dolbyAtmos = dolbyAtmos,
            onToggleDolbyAtmos = { on ->
                dolbyAtmos = on
                AudioQuality.setDolbyAtmos(on)
            },
            lyricsSourceMode = lyricsSourceMode,
            onSelectLyricsSource = { m ->
                lyricsSourceMode = m
                LyricsSourcePref.setMode(m)
            },
            lyricFontPreset = lyricFontPreset,
            onSelectLyricFont = { f ->
                lyricFontPreset = f
                LyricFont.setPreset(f)
            },
            onExportLogs = {
                showAbout = false
                onExportLogs()
            },
            onLogout = {
                showAbout = false
                onLogout()
            },
            onDismiss = { showAbout = false }
        )
    }
}

// ---------------- 账户 / 设置弹层（2.0.0 Beta2 重写为 M3，见 SettingsSheet.kt） ----------------

@Composable
private fun AboutDialog(
    versionName: String,
    preferStandard: Boolean,
    onSelectQuality: (Boolean) -> Unit,
    lyricsSourceMode: LyricsSourcePref.Mode,
    onSelectLyricsSource: (LyricsSourcePref.Mode) -> Unit,
    lyricFontPreset: LyricFont.Preset,
    onSelectLyricFont: (LyricFont.Preset) -> Unit,
    onExportLogs: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161E),
        title = { Text("Yuno", color = Color.White) },
        text = {
            Column {
                Text(
                    text = "版本 v$versionName",
                    color = YunoTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
                // 播放音质（真机需求：音质选择放这个弹窗里）
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "播放音质",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityChip(
                        label = "高（256k）",
                        selected = !preferStandard,
                        onClick = { onSelectQuality(false) }
                    )
                    QualityChip(
                        label = "标准（64k）",
                        selected = preferStandard,
                        onClick = { onSelectQuality(true) }
                    )
                }
                // 歌词源（真机需求：设置页自由选择歌词源/获取方式）
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "歌词源",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityChip(
                        label = "自动",
                        selected = lyricsSourceMode == LyricsSourcePref.Mode.AUTO,
                        onClick = { onSelectLyricsSource(LyricsSourcePref.Mode.AUTO) }
                    )
                    QualityChip(
                        label = "仅 Apple",
                        selected = lyricsSourceMode == LyricsSourcePref.Mode.APPLE_ONLY,
                        onClick = { onSelectLyricsSource(LyricsSourcePref.Mode.APPLE_ONLY) }
                    )
                    QualityChip(
                        label = "仅 LRCLIB",
                        selected = lyricsSourceMode == LyricsSourcePref.Mode.LRCLIB_ONLY,
                        onClick = { onSelectLyricsSource(LyricsSourcePref.Mode.LRCLIB_ONLY) }
                    )
                    QualityChip(
                        label = "仅网易云",
                        selected = lyricsSourceMode == LyricsSourcePref.Mode.NETEASE_ONLY,
                        onClick = { onSelectLyricsSource(LyricsSourcePref.Mode.NETEASE_ONLY) }
                    )
                }
                // 歌词字体（真机需求：音质下方加字体选择）
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "歌词字体",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityChip(
                        label = "系统默认",
                        selected = lyricFontPreset == LyricFont.Preset.SYSTEM,
                        onClick = { onSelectLyricFont(LyricFont.Preset.SYSTEM) }
                    )
                    QualityChip(
                        label = "内置",
                        selected = lyricFontPreset != LyricFont.Preset.SYSTEM,
                        onClick = { onSelectLyricFont(LyricFont.Preset.SF_PRO) }
                    )
                }
                // 选了内置 → 展开具体字体（真机需求）
                if (lyricFontPreset != LyricFont.Preset.SYSTEM) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QualityChip(
                            label = "SF Pro",
                            selected = lyricFontPreset == LyricFont.Preset.SF_PRO,
                            onClick = { onSelectLyricFont(LyricFont.Preset.SF_PRO) }
                        )
                        QualityChip(
                            label = "Ping Fang SC",
                            selected = lyricFontPreset == LyricFont.Preset.PING_FANG,
                            onClick = { onSelectLyricFont(LyricFont.Preset.PING_FANG) }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "遇到问题时点「导出日志」，把运行记录发给开发者排查" +
                        "（不含账号令牌等敏感信息）",
                    color = YunoTextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onExportLogs) {
                Text("导出日志", color = YunoAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onLogout) {
                Text("退出登录", color = Color(0xFFE57373))
            }
        }
    )
}

@Composable
private fun QualityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) YunoAccent.copy(alpha = 0.18f)
                else Color.White.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            color = if (selected) YunoAccent else YunoTextSecondary,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

// ---------------- 头部（折叠标题 + 账号徽章） ----------------

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun LibraryHeader(
    accountName: String,
    subscriptionActive: Boolean,
    subscriptionTier: String,
    scrollProvider: () -> Float,
    hazeState: HazeState,
    onBadgeClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CollapsingHeader(
            title = "资料库",
            collapseFractionProvider = scrollProvider,
            modifier = Modifier.weight(1f)
        ) { fraction ->
            // 只在订阅有效时显示状态；没有状态信息就不显示（不要提示语）
            if (subscriptionActive) {
                Text(
                    text = buildString {
                        append("Apple Music 订阅有效")
                        if (subscriptionTier.isNotBlank()) append(" · ").append(subscriptionTier)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = YunoAccent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = ((1f - fraction) * 4f).dp)
                )
            }
        }

        // 账号徽章：液态玻璃圆 + 精致音符/首字母；点按 = 账户/关于弹层
        val badgeStyle = rememberLiquidGlassStyle(shape = CircleShape)
        Box(
            Modifier
                .size(48.dp)
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = badgeStyle
                )
                .clip(CircleShape)
                .clickable(onClick = onBadgeClick),
            contentAlignment = Alignment.Center
        ) {
            if (accountName.isNotBlank()) {
                Text(
                    text = accountName.first().uppercaseChar().toString(),
                    color = Color.White,
                    fontSize = MaterialTheme.typography.titleLarge.fontSize,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = "账号",
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = Color.White,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
    )
}

// ---------------- 行组件 ----------------

@Composable
private fun SongRowWithHeart(
    track: Track,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            TrackRow(
                track = track,
                onClick = onClick,
                trailingText = formatDuration(track.durationMs),
                onAddToQueue = onAddToQueue,
                onPlayNext = onPlayNext
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏",
                tint = if (isFavorite) YunoAccent else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(
            url = playlist.artworkUrl,
            modifier = Modifier.size(54.dp),
            shape = RoundedCornerShape(12.dp),
            borderHighlight = false
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = buildString {
                    if (playlist.curator.isNotBlank()) append(playlist.curator)
                    if (playlist.trackCount > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("${playlist.trackCount} 首")
                    }
                },
                color = YunoTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun LibraryLoading() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        YunoLoadingCircle()
        Text(
            text = "正在同步资料库…",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** 播放列表竖卡（资料库"全部"页置顶货架） */
@Composable
private fun PlaylistTile(playlist: Playlist, onClick: () -> Unit) {
    Column(
        Modifier
            .width(128.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 6.dp)
    ) {
        ArtworkImage(
            url = playlist.artworkUrl,
            modifier = Modifier.size(128.dp),
            shape = RoundedCornerShape(20.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        if (playlist.curator.isNotBlank()) {
            Text(
                text = playlist.curator,
                color = YunoTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
