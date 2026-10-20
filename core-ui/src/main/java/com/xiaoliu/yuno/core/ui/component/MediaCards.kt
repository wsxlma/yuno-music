package com.xiaoliu.yuno.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Artist
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary

// ======================= 共享媒体卡片（Material 3 质感） =======================
// 首页 / 搜索 / 专辑详情 / 资料库四处复用。
// 质感规则照旧：不用纯色块，封面圆角 + 细高光描边 + 阴影；文字信息压暗克制。

/** 无封面时的兜底渐变（深紫灰，不喧宾夺主） */
private val ArtworkPlaceholder = Brush.linearGradient(
    listOf(Color(0xFF2A2438), Color(0xFF17151F))
)

@Composable
fun ArtworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    borderHighlight: Boolean = true
) {
    val base = modifier
        .clip(shape)
        .then(
            if (borderHighlight) {
                Modifier
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))
                        ),
                        shape
                    )
            } else Modifier
        )

    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = base.background(ArtworkPlaceholder)
    )
}

/**
 * 专辑卡（竖版）：封面 + 标题 + 歌手。首页「为你推荐」、搜索专辑 Tab、资料库网格都用它。
 */
@Composable
fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSize: Dp = 156.dp,
    subtitleOverride: String? = null
) {
    Column(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        ArtworkImage(
            url = album.artworkUrl,
            modifier = Modifier
                .size(coverSize)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(24.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.5f)
                )
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = album.title,
            color = Color.White,
            fontSize = MaterialTheme.typography.titleSmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitleOverride ?: album.artist,
            color = YunoTextSecondary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 横向歌曲卡（首页「继续播放」用）：方形封面 + 标题 + 歌手。
 */
@Composable
fun TrackCard(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverSize: Dp = 132.dp
) {
    Column(
        modifier = modifier.clickable(onClick = onClick)
    ) {
        ArtworkImage(
            url = track.artworkUrl,
            modifier = Modifier
                .size(coverSize)
                .shadow(
                    elevation = 12.dp,
                    shape = RoundedCornerShape(22.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.4f),
                    spotColor = Color.Black.copy(alpha = 0.45f)
                ),
            shape = RoundedCornerShape(22.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = track.title,
            color = Color.White,
            fontSize = MaterialTheme.typography.titleSmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = YunoTextSecondary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 歌曲行：小封面 + 标题/歌手 + 尾部信息（时长/序号）。
 * 搜索歌曲 Tab、专辑曲目、资料库歌曲列表都用它。
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIndex: Int? = null,
    leadingArtwork: Boolean = true,
    trailingText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    playing: Boolean = false,
    /** 长按 1.15s 弹出的 M3 菜单动作（加入待播列表 / 插播）；都传 null 则不弹菜单 */
    onAddToQueue: ((Track) -> Unit)? = null,
    onPlayNext: ((Track) -> Unit)? = null
) {
    var menuVisible by remember { mutableStateOf(false) }
    val hasMenu = onAddToQueue != null || onPlayNext != null
    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .then(
                    if (hasMenu) {
                        // 官方 detectTapGestures：onTap = 单击播放，onLongPress = 长按弹菜单。
                        // 真机 bug（1.0.70 自写手势）：withTimeoutOrNull 超时返回 null 被当"长按"
                        // + 列表 fling 取消触摸 → 单击也弹菜单，播放功能全坏。
                        // 官方 API 对 fling/cancel 有完整处理，单击/长按语义天然正确。
                        // 列表快速滑动中取消 = 什么都不触发（不点、不按），绝不误弹。
                        Modifier.pointerInput(track) {
                            detectTapGestures(
                                onTap = { onClick() },
                                onLongPress = { menuVisible = true }
                            )
                        }
                    } else Modifier.clickable(onClick = onClick)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        // 序号与封面独立判断（真机 bug：曾经是 when 互斥，导致"序号+封面"只显示一个）
        if (leadingIndex != null) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = leadingIndex.toString(),
                    color = if (playing) YunoAccent else YunoTextSecondary,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        if (leadingArtwork) {
            ArtworkImage(
                url = track.artworkUrl,
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(10.dp),
                borderHighlight = false
            )
            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (playing) YunoAccent else Color.White,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist,
                color = YunoTextSecondary,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        trailingText?.let {
            Spacer(Modifier.width(10.dp))
            Text(
                text = it,
                color = YunoTextSecondary,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            )
        }
        trailing?.let { it() }
        }

        // M3 风格长按菜单：加入待播列表 / 插播（真机需求）
        if (hasMenu) {
            DropdownMenu(
                expanded = menuVisible,
                onDismissRequest = { menuVisible = false },
                containerColor = Color(0xFF1E1E28)
            ) {
                onAddToQueue?.let { act ->
                    DropdownMenuItem(
                        text = { Text("加入待播列表", color = Color.White) },
                        onClick = {
                            menuVisible = false
                            act(track)
                        }
                    )
                }
                onPlayNext?.let { act ->
                    DropdownMenuItem(
                        text = { Text("插播（下一首）", color = Color.White) },
                        onClick = {
                            menuVisible = false
                            act(track)
                        }
                    )
                }
            }
        }
    }
}

/** 圆形头像歌手（搜索歌手 Tab） */
@Composable
fun ArtistRow(
    artist: Artist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = artist.artworkUrl,
            contentDescription = null,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(ArtworkPlaceholder)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = Color.White,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist.genre.ifBlank { "歌手" },
                color = YunoTextSecondary,
                fontSize = MaterialTheme.typography.bodySmall.fontSize
            )
        }
    }
}
