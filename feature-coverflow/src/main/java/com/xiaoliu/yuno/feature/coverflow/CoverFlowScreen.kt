package com.xiaoliu.yuno.feature.coverflow

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.Track
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
// 3D 封面橱窗（书架式选歌页）—— 手机版（参考 QQ 音乐 iPhone Duo Cover Flow）
//
// 排列（截图实测）：
//   中心书正对观众、最大；紧邻书向外转 ~52°、再外 ~65-71°、最外趋近 88°（书脊朝前）；
//   中心两侧留空当（被推开的感觉）；地面投影。
// 切换动画（视频实测）：
//   横向拖 = 传送带，书随与中心的距离连续「转向 + 缩放 + 变亮」；拖住的书可拎起；
//   松手落到最近槽位（新中心立即播放，用户钦定）。上拖过阈值 = 提交（播放）。
// ============================================================================

/** 横拖一格距离系数：拖动 baseSize×0.62 = 换一张 */
private const val SLOT_DRAG = 0.62f

/** 上拖提交阈值（占屏高比例） */
private const val COMMIT_RATIO = 0.24f

@Composable
fun CoverFlowScreen(
    vm: CoverFlowViewModel,
    onExit: () -> Unit,
    onCommitted: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val queue = playback.queue
    val currentIndex = playback.queueIndex.coerceAtLeast(0)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B10))
    ) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val baseSize = hPx * 0.46f
        val shelfCy = hPx * 0.46f

        // —— 暗角背景 ——
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.30f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.80f)
                    )
                )
        )

        // —— 顶栏：左收起 ∨ / 右列表 ——
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TopGlassButton(Icons.Rounded.KeyboardArrowDown, "收起") { onExit() }
            TopGlassButton(Icons.Rounded.Menu, "列表") { }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "队列是空的，先去放首歌吧",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            return@BoxWithConstraints
        }

        // —— 浮点中心 ——
        val floatIndex = remember { Animatable(currentIndex.toFloat()) }
        LaunchedEffect(currentIndex) {
            if (abs(floatIndex.value - currentIndex) > 0.01f) {
                floatIndex.animateTo(currentIndex.toFloat(), tween(480, easing = FastOutSlowInEasing))
            }
        }
        val scope = rememberCoroutineScope()

        var liftedIndex by remember { mutableStateOf(-1) }
        val liftPx = remember { Animatable(0f) }

        var axis by remember { mutableStateOf(0) }   // 0 未定 / 1 横 / 2 纵
        var dragStartX by remember { mutableFloatStateOf(0f) }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(queue.size, baseSize) {
                    detectDragGestures(
                        onDragStart = { start ->
                            axis = 0
                            dragStartX = start.x
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            if (axis == 0) {
                                axis = if (abs(amount.x) > abs(amount.y)) 1 else 2
                                if (axis == 2) {
                                    val posesNow = computePoses(queue.size, floatIndex.value, wPx / 2f, shelfCy, baseSize)
                                    liftedIndex = posesNow.indices
                                        .minByOrNull { abs(posesNow[it].x - dragStartX) } ?: -1
                                }
                            }
                            when (axis) {
                                1 -> {
                                    val frac = floatIndex.value - amount.x / (baseSize * SLOT_DRAG)
                                    scope.launch {
                                        floatIndex.snapTo(frac.coerceIn(-0.4f, queue.size - 1 + 0.4f))
                                    }
                                }
                                2 -> {
                                    scope.launch { liftPx.snapTo((liftPx.value - amount.y).coerceAtLeast(0f)) }
                                }
                            }
                        },
                        onDragEnd = {
                            when (axis) {
                                1 -> {
                                    val target = floatIndex.value.roundToInt().coerceIn(0, queue.lastIndex)
                                    scope.launch {
                                        floatIndex.animateTo(target.toFloat(), tween(320, easing = FastOutSlowInEasing))
                                    }
                                    if (target != currentIndex) vm.playAt(target)
                                }
                                2 -> {
                                    val lifted = liftedIndex
                                    val committed = liftPx.value > hPx * COMMIT_RATIO
                                    if (committed && lifted in queue.indices) {
                                        if (lifted == currentIndex) vm.togglePlayPause()
                                        else vm.playAt(lifted)
                                        onCommitted(lifted)
                                    }
                                    scope.launch { liftPx.animateTo(0f, tween(260, easing = FastOutSlowInEasing)) }
                                    liftedIndex = -1
                                }
                            }
                            axis = 0
                        },
                        onDragCancel = {
                            scope.launch { liftPx.animateTo(0f, tween(200)) }
                            scope.launch {
                                val target = floatIndex.value.roundToInt().coerceIn(0, queue.lastIndex)
                                floatIndex.animateTo(target.toFloat(), tween(240))
                            }
                            liftedIndex = -1
                            axis = 0
                        }
                    )
                }
        ) {
            // 本次渲染姿态（拎起的书带 lift）
            val poses = computePoses(queue.size, floatIndex.value, wPx / 2f, shelfCy, baseSize)
            val posesWithLift = poses.mapIndexed { i, p ->
                if (i == liftedIndex) p.copy(lift = liftPx.value) else p
            }

            // —— 书脊/页块 + 地面投影（垫在最下层的统一 Canvas）——
            Canvas(Modifier.fillMaxSize()) {
                posesWithLift.forEach { p ->
                    if (p.alpha <= 0.02f) return@forEach
                    val half = p.size / 2f
                    val theta = p.rotationY * (Math.PI.toFloat() / 180f)
                    val stripW = p.size * BOOK_THICKNESS * sin(abs(theta)).coerceAtLeast(0.08f)
                    val topSkew = stripW * 0.10f
                    val top = p.y - half - p.lift
                    val bottom = p.y + half - p.lift * 0.94f
                    val side = Path().apply {
                        if (p.rotationY >= 0f) {
                            val fx = p.x - half
                            moveTo(fx, top)
                            lineTo(fx + stripW * 0.82f - topSkew, top + p.lift * 0.04f)
                            lineTo(fx + stripW * 0.82f + topSkew, bottom)
                            lineTo(fx, bottom)
                        } else {
                            val rx = p.x + half
                            moveTo(rx, top)
                            lineTo(rx - stripW * 0.82f + topSkew, top + p.lift * 0.04f)
                            lineTo(rx - stripW * 0.82f - topSkew, bottom)
                            lineTo(rx, bottom)
                        }
                        close()
                    }
                    drawPath(
                        side,
                        brush = Brush.verticalGradient(
                            listOf(
                                Color(0xFF2E2E3A).copy(alpha = p.alpha),
                                Color(0xFF14141B).copy(alpha = p.alpha)
                            )
                        )
                    )
                    drawOval(
                        color = Color.Black.copy(alpha = 0.34f * p.alpha),
                        topLeft = Offset(p.x - half * 0.92f, p.y + half - p.size * 0.10f),
                        size = Size(p.size * 0.84f, p.size * 0.12f)
                    )
                }
            }

            // —— 书本（按离中心距离升序画，中心书最后画 = 最上层）——
            posesWithLift.indices
                .sortedBy { abs(it - floatIndex.value) }
                .forEach { i ->
                    val t = queue.getOrNull(i) ?: return@forEach
                    val p = posesWithLift[i]
                    if (p.alpha <= 0.02f) return@forEach
                    BookFace(track = t, pose = p, z = 1f - abs(i - floatIndex.value).coerceAtMost(2f) * 0.3f)
                }

            // —— 中心信息区 ——
            val centerTrack = queue.getOrNull(floatIndex.value.roundToInt())
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(density) { (hPx * 0.075f).toDp() }),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BarsIcon(playing = playback.isPlaying)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = centerTrack?.title.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = centerTrack?.artist.orEmpty(),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Icon(
                    imageVector = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "播放/暂停",
                    tint = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/** 顶部玻璃圆钮 */
@Composable
private fun TopGlassButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = 0.85f))
    }
}

/** 单本书：封面 + rotationY 3D（右半书架绕左缘转出、左半绕右缘转出，形成扇） */
@Composable
private fun BookFace(track: Track, pose: BookPose, z: Float) {
    val density = LocalDensity.current
    val toRight = pose.rotationY >= 0f
    AsyncImage(
        model = track.artworkUrl,
        contentDescription = track.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .offset {
                IntOffset(
                    pose.x.roundToInt() - (pose.size / 2f).roundToInt(),
                    (pose.y - pose.size / 2f - pose.lift).roundToInt()
                )
            }
            .size(with(density) { pose.size.toDp() })
            .zIndex(z)
            .graphicsLayer {
                rotationY = pose.rotationY
                transformOrigin = if (toRight) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
                cameraDistance = 15f * this.density
                this.alpha = pose.alpha
            }
    )
}

/** 三格跳动播放条 */
@Composable
private fun BarsIcon(playing: Boolean) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        listOf(10.dp, 16.dp, 22.dp).forEach { h ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(h)
                    .graphicsLayer { alpha = if (playing) 0.95f else 0.55f }
                    .background(Color.White, RoundedCornerShape(2.dp))
            )
        }
    }
}