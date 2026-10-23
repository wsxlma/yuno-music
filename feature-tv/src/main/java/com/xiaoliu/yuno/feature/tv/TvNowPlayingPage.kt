package com.xiaoliu.yuno.feature.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.ArtworkImage
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import com.xiaoliu.yuno.feature.player.WheelLyrics
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * TV「正在播放」页 —— 电视版横屏歌词页（真机口径）：
 *   · 封面轮播：当前封面居中，左右各露一半上一曲/下一曲；切歌时按方向滑入滑出
 *   · 封面外一圈流光「灯条」（漩涡渐变转圈 + 呼吸光晕）
 *   · 封面正下方：上一曲 / 暂停播放 / 下一曲，进度条与封面严格等宽
 *   · 右侧整块 = 滚筒逐字歌词（与手机横屏同一引擎，逐字卡拉 OK 必显）
 * 遥控器：方向键走焦点，OK 播放/切换，菜单键与长按 OK 语义见首页。
 */
@Composable
fun TvNowPlayingPage(vm: TvViewModel) {
    val track by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)
    val pos by vm.positionMsFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val playing by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)
    val queue by remember(vm.playback) {
        vm.playback.map { it.queue }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val queueIndex by remember(vm.playback) {
        vm.playback.map { it.queueIndex }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = -1)

    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsLoading by vm.lyricsLoading.collectAsStateWithLifecycle()
    val timeline = remember(lyrics) { lyrics?.let { LyricsTimeline(it.lines) } }
    val posState = rememberUpdatedState(pos)

    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "没有正在播放的内容",
                color = YunoTextSecondary,
                style = MaterialTheme.typography.titleLarge
            )
        }
        return
    }
    // delegated 属性不能智能转换，落成本地 val（本地 val 可以）再用
    val currentTrack = track ?: return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 封面尺寸：跟着屏高走、留白足，电视上不顶天不压地
        val artWidth = minOf(maxWidth * 0.26f, maxHeight * 0.40f)

        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 76.dp, end = 48.dp, top = 24.dp, bottom = 36.dp)
        ) {
            // ── 左列：封面轮播 + 控制 ──
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(artWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CoverCarousel(
                    queue = queue,
                    currentIndex = queueIndex,
                    artWidth = artWidth
                )

                Spacer(Modifier.height(36.dp))

                Text(
                    text = currentTrack.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = currentTrack.artist,
                    color = YunoTextSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(30.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(34.dp)
                ) {
                    TvRoundButton(Icons.Rounded.SkipPrevious, "上一曲") { vm.previous() }
                    TvPlayPauseButton(playing = playing) { vm.togglePlayPause() }
                    TvRoundButton(Icons.Rounded.SkipNext, "下一曲") { vm.next() }
                }

                Spacer(Modifier.height(30.dp))

                // 进度条：与封面严格等宽（本 Column 宽度 = 封面宽度）
                TrackProgressRow(
                    positionMs = pos,
                    durationMs = currentTrack.durationMs,
                    onSeek = { vm.seekTo(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.width(64.dp))

            // ── 右列：滚筒逐字歌词 ──
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
            ) {
                when {
                    lyricsLoading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { YunoLoadingCircle(size = 42.dp) }

                    timeline != null -> WheelLyrics(
                        timeline = timeline,
                        positionMs = posState,
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "暂无歌词",
                            color = YunoTextSecondary,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    }
}

/** 封面轮播：被 AnimatedContent 三连拍整体切入切出，方向跟着队列下标走 */
@Composable
private fun CoverCarousel(queue: List<Track>, currentIndex: Int, artWidth: Dp) {
    val current = queue.getOrNull(currentIndex)
    val palette = rememberArtworkPalette(current?.artworkUrl)

    AnimatedContent(
        targetState = currentIndex,
        transitionSpec = {
            // 下标变大 = 下一曲（新封面从右进来）；变小 = 上一曲（从左进来）
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(
                animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
                initialOffsetX = { dir * it }
            ) + fadeIn(tween(420))) togetherWith
                (slideOutHorizontally(
                    animationSpec = tween(420, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
                    targetOffsetX = { -dir * it / 3 }
                ) + fadeOut(tween(280)))
        },
        label = "coverCarousel"
    ) { idx ->
        val cur = queue.getOrNull(idx)
        Box(modifier = Modifier.size(artWidth), contentAlignment = Alignment.Center) {
            queue.getOrNull(idx - 1)?.let { prev ->
                SideCover(
                    track = prev,
                    size = artWidth * 0.52f,
                    modifier = Modifier.offset(x = -artWidth * 0.72f)
                )
            }
            queue.getOrNull(idx + 1)?.let { next ->
                SideCover(
                    track = next,
                    size = artWidth * 0.52f,
                    modifier = Modifier.offset(x = artWidth * 0.72f)
                )
            }
            if (cur != null) {
                // 灯条 + 主封面（灯条垫底，呼吸光晕从封面四周溢出来）
                Box(contentAlignment = Alignment.Center) {
                    CoverLightRing(
                        color = palette.vibrant,
                        modifier = Modifier.size(artWidth + 26.dp)
                    )
                    ArtworkImage(
                        url = cur.artworkUrl,
                        modifier = Modifier.size(artWidth),
                        shape = RoundedCornerShape(22.dp)
                    )
                }
            }
        }
    }
}

/** 侧封面：缩小 + 压暗 + 轻模糊，焦点留在中间 */
@Composable
private fun SideCover(track: Track, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = track.artworkUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .alpha(0.45f)
            .blur(5.dp)
    )
}

/**
 * 封面灯条：一圈沿封面的流光（漩涡渐变整圈转）+ 外侧呼吸柔光。
 * 流光色 = 当前封面主色（经过面积加权取色的 vibrant）。
 */
@Composable
private fun CoverLightRing(color: Color, modifier: Modifier = Modifier) {
    val ring = rememberInfiniteTransition(label = "coverRing")
    val angle by ring.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "ringAngle"
    )
    val halo by ring.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(3_600, easing = LinearEasing), RepeatMode.Reverse),
        label = "ringHalo"
    )

    Canvas(modifier) {
        val r = size.minDimension / 2f
        // 外圈呼吸柔光（灯带的"光晕"层）
        drawCircle(
            color = color.copy(alpha = 0.26f * (halo - 0.72f) / 0.36f),
            radius = r,
            style = Stroke(width = 12.dp.toPx())
        )
        // 流光层：漩涡渐变（亮→暗→亮→暗），整圈旋转
        rotate(angle, pivot = center) {
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f),
                        color.copy(alpha = 0.25f),
                        Color.White.copy(alpha = 0.85f),
                        color.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.95f)
                    ),
                    center = center
                ),
                radius = r * halo,
                style = Stroke(width = 3.dp.toPx())
            )
        }
    }
}

@Composable
private fun TrackProgressRow(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val duration = durationMs.coerceAtLeast(1L)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatClock(positionMs),
            color = YunoTextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
        Slider(
            value = (positionMs / 1000f).coerceIn(0f, duration / 1000f),
            onValueChange = { sec -> onSeek((sec * 1000L).toLong()) },
            valueRange = 0f..(duration / 1000f),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = YunoAccent,
                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
            )
        )
        Text(
            text = formatClock(durationMs),
            color = YunoTextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TvRoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .tvFocusable(onClick = onClick, shape = CircleShape)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun TvPlayPauseButton(playing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(86.dp)
            .tvFocusable(onClick = onClick, shape = CircleShape)
            .clip(CircleShape)
            .background(YunoAccent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = if (playing) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(38.dp)
        )
    }
}