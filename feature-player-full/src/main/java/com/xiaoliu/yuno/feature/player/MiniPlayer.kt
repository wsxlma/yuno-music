package com.xiaoliu.yuno.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

// ============================================================================
// 迷你播放器（迷你控制器）—— 液态玻璃胶囊：
//   真机反馈修正：整体做大（68 高）、耳角收小（20dp 圆角）、封面收小（40）、
//   右侧按钮组仿 Apple Music：左边启停（圆润纯白图标）+ 右边双三角下一曲。
//   玻璃工艺统一走 rememberLiquidGlassStyle。
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
fun MiniPlayer(
    vm: PlayerViewModel,
    onExpand: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onDragUpdate: ((totalPx: Float) -> Unit)? = null,
    onDragEnd: ((velocityPxPerSec: Float) -> Unit)? = null
) {
    val track by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)
    val t = track ?: return

    // R 角 26dp：真机反馈"20 太小不圆润"，回到圆润但不全圆的中间值
    val capsule = RoundedCornerShape(26.dp)
    val barStyle = rememberLiquidGlassStyle(shape = capsule)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 6.dp)
            .height(64.dp)
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = barStyle
            )
            .clip(capsule)
            // 上滑打开歌词页（Apple Music 式手势，跟手）：有 onDragUpdate 时
            // 由宿主实时驱动播放页滑入（播放页跟着手上移），否则退化为阈值触发
            .pointerInput(onExpand, onDragUpdate != null) {
                var total = 0f
                var lastTime = 0L
                var velocity = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        total = 0f
                        lastTime = 0L
                        velocity = 0f
                    },
                    onVerticalDrag = { change, dragAmount ->
                        total += dragAmount
                        // 速度：位移 / 这一帧的时间间隔（px/s），指数平滑防抖
                        if (lastTime > 0L) {
                            val dt = change.uptimeMillis - lastTime
                            if (dt > 0) {
                                velocity = velocity * 0.6f + (dragAmount / dt * 1000f) * 0.4f
                            }
                        }
                        lastTime = change.uptimeMillis
                        onDragUpdate?.invoke(total)
                    },
                    onDragEnd = {
                        when {
                            onDragEnd != null -> onDragEnd.invoke(velocity)
                            total < -120f -> onExpand()
                        }
                    }
                )
            }
            .clickable { onExpand() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = t.artworkUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = t.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = t.artist,
                    color = YunoTextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 按钮组（仿 Apple Music 迷你条）：启停 + 下一曲（双三角）
            MiniControlButtons(vm)
        }

        // 微缩进度线（贴胶囊底边）
        MiniProgressLine(
            vm = vm,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )
    }
}

/** 右侧按钮组：左 = 播放/暂停（圆润纯白图标），右 = 双三角下一曲（同尺寸同圆润度） */
@Composable
private fun MiniControlButtons(vm: PlayerViewModel) {
    val isPlaying by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable { vm.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable { vm.next() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.FastForward,
                contentDescription = "下一首",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 2dp 微缩进度线：50ms 一条的窄流只喂给这条线，迷你播放器其余部分不重组 */
@Composable
private fun MiniProgressLine(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val position by vm.positionMsFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val duration by vm.durationMsFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Canvas(modifier = modifier.height(7.dp)) {
        val h = 2.dp.toPx()
        val y = size.height - h
        val radius = CornerRadius(h / 2f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            topLeft = Offset(0f, y),
            size = Size(size.width, h),
            cornerRadius = radius
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.85f),
            topLeft = Offset(0f, y),
            size = Size(size.width * fraction, h),
            cornerRadius = radius
        )
    }
}
