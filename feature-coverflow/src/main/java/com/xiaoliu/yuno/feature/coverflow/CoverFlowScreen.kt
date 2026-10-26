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
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.KeyboardArrowDown
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 3D 封面橱窗（书架式选歌页）—— 手机横屏播放页的左半区（与右侧歌词同屏）
//
// 书架（真机 v2 规格）：
//   · 侧书 = 直插书脊（不加旋转、不做斜纸片）：一根窄竖条 + 竖排歌名，直进直出
//   · 中央书 = 完整方形封面正面示人；滑到中央时封面直接在书脊位上「亮出」
//     （书脊淡出、封面淡入 + 轻微放大），转场只有淡入淡出+缩放，无纸片旋转
//   · 切换 = 传送带横拖；竖拖拎起 → 过阈值提交（立即播放，用户钦定）
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
        val shelfCy = hPx * 0.47f

        // —— 暗角背景 ——
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.50f),
                        0.30f to Color.Transparent,
                        0.80f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f)
                    )
                )
        )

        // —— 左上：收起 ∨（唯一的按钮；原「菜单」钮已按真机反馈移除）——
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.TopStart
        ) {
            TopGlassButton(Icons.Rounded.KeyboardArrowDown, "收起") { onExit() }
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
                                    // 浏览货架而已：只吸附到最近槽位，**不切歌**
                                    //（真机 v4：把歌挪到主位就自动播放是擅作主张，撤掉）
                                    val target = floatIndex.value.roundToInt().coerceIn(0, queue.lastIndex)
                                    scope.launch {
                                        floatIndex.animateTo(target.toFloat(), tween(320, easing = FastOutSlowInEasing))
                                    }
                                }
                                2 -> {
                                    val lifted = liftedIndex
                                    val committed = liftPx.value > hPx * COMMIT_RATIO
                                    if (committed && lifted in queue.indices) {
                                        // 放到主位 = 切到这首；放到后面 = 作为下一曲
                                        val posesNow = computePoses(queue.size, floatIndex.value, wPx / 2f, shelfCy, baseSize)
                                        val dropX = posesNow.getOrNull(lifted)?.x ?: wPx / 2f
                                        val atCenter = abs(dropX - wPx / 2f) < wPx * 0.25f
                                        if (atCenter) vm.playAt(lifted) else vm.playNextAt(lifted)
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
            val poses = computePoses(queue.size, floatIndex.value, wPx / 2f, shelfCy, baseSize)
            val posesWithLift = poses.mapIndexed { i, p ->
                if (i == liftedIndex) p.copy(lift = liftPx.value) else p
            }

            // —— 地面投影 ——
            Canvas(Modifier.fillMaxSize()) {
                posesWithLift.forEach { p ->
                    if (p.alpha <= 0.02f) return@forEach
                    val half = p.size / 2f
                    drawOval(
                        color = Color.Black.copy(alpha = 0.32f * p.alpha),
                        topLeft = Offset(p.x - half * 0.92f, p.y + half - p.size * 0.10f - p.lift * 0.6f),
                        size = Size(p.size * 0.84f, p.size * 0.12f)
                    )
                }
            }

            // —— 书本（真 3D 双面盒：页块平面 + 封面平面共轴旋转；按距离升序画）——
            posesWithLift.indices
                .sortedBy { abs(it - floatIndex.value) }
                .forEach { i ->
                    val t = queue.getOrNull(i) ?: return@forEach
                    val p = posesWithLift[i]
                    if (p.alpha <= 0.02f) return@forEach
                    Book3D(
                        track = t,
                        pose = p,
                        z = 1f - abs(i - floatIndex.value).coerceAtMost(2f) * 0.3f
                    )
                }

            // —— 中心信息区 ——
            val centerTrack = queue.getOrNull(floatIndex.value.roundToInt())
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(density) { (hPx * 0.045f).toDp() }),
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

/**
 * 真 3D 盒子：两个平面共轴旋转 ——
 *   ① 页块平面（书脊包边）：挂在书的**外缘**（与参考一致：左侧书的书脊在左缘、
 *      右侧书的书脊在右缘），始终与封面保持 90°夹角；颜色 = 该专辑主色的
 *      深浅三档渐变（真机要求：包边也要填充主题色，不能是死灰）。
 *   ② 封面平面（专辑贴图）带着包边一起逐帧旋转。
 * 停靠姿态：rotationY +18°、rotationX 4°（不完全正对，左下近、右上往里压）。
 */
@Composable
private fun Book3D(track: Track, pose: BookPose, z: Float) {
    val density = LocalDensity.current
    val palette = rememberArtworkPalette(track.artworkUrl)
    val size = pose.size
    val thickPx = (size * BOOK_THICKNESS * 3.6f).coerceAtLeast(size * 0.16f)
    val pivotLeft = pose.pivotLeft
    val origin = if (pivotLeft) TransformOrigin(0f, 0.5f) else TransformOrigin(1f, 0.5f)
    val offX = pose.x - size / 2f
    val offY = pose.y - size / 2f - pose.lift
    val camDist = 12f * density.density

    // ① 页块平面（外缘，主题色渐变）
    val stripLeft = if (pivotLeft) offX + size - thickPx else offX
    Box(
        Modifier
            .offset { IntOffset(stripLeft.roundToInt(), offY.roundToInt()) }
            .width(with(density) { thickPx.toDp() })
            .height(with(density) { size.toDp() })
            .zIndex(z - 0.02f)
            .graphicsLayer {
                rotationY = pose.rotationY + (if (pivotLeft) -90f else 90f)
                rotationX = pose.rotationX
                transformOrigin = if (pivotLeft) TransformOrigin(1f, 0.5f) else TransformOrigin(0f, 0.5f)
                cameraDistance = camDist
                this.alpha = pose.alpha
            }
            .background(
                Brush.verticalGradient(
                    listOf(
                        lerp(palette.dominant, Color.White, 0.14f),
                        palette.dominant,
                        lerp(palette.dominant, Color.Black, 0.42f)
                    )
                )
            )
    )

    // ② 封面平面
    AsyncImage(
        model = track.artworkUrl,
        contentDescription = track.title,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
            .size(with(density) { size.toDp() })
            .zIndex(z)
            .graphicsLayer {
                rotationY = pose.rotationY
                rotationX = pose.rotationX
                transformOrigin = origin
                cameraDistance = camDist
                this.alpha = pose.alpha
            }
    )
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