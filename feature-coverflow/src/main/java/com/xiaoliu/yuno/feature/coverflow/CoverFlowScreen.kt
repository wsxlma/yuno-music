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
import androidx.compose.foundation.layout.fillMaxHeight
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

            // —— 书本（单体模型：一个世界变换 + 开合铰链；按距离升序画）——
            posesWithLift.indices
                .sortedBy { abs(it - floatIndex.value) }
                .forEach { i ->
                    val t = queue.getOrNull(i) ?: return@forEach
                    val p = posesWithLift[i]
                    if (p.alpha <= 0.02f) return@forEach
                    val d = i - floatIndex.value
                    Book3D(
                        track = t,
                        pose = p,
                        openness = (1f - abs(d).coerceAtMost(0.5f) * 2f).coerceIn(0f, 1f),
                        z = 1f - abs(d).coerceAtMost(2f) * 0.3f
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
 * 单本 3D 书模型（真机 v5 重构）：一本书 = 一个整体。
 *
 * 变换层级：
 *   · 外层 = 世界姿态（唯一旋转来源：rotationY 扇开 + rotationX 后仰 + 透视），
 *     封面、书脊、页块**全部**挂在这一层下一起转 —— 治好「书脊和封面各转各的」
 *   · 内层 = 「开合」铰链：只有封面绕右缘翻开这一个局部自由度（真书打开的语义，
 *     不是部件乱转）
 *
 * 到主位（openness→1）= 打开：
 *   封面自右缘向前翻（露页块视角）→ 封面与页块之间出现**书脊大缝**；
 *   书脊在书的**右侧**、横排歌名；页块 + 书脊全部用专辑主色填充。
 * 离开主位（openness→0）= 合拢成带右缘书脊的一本。
 */
@Composable
private fun Book3D(track: Track, pose: BookPose, openness: Float, z: Float) {
    val density = LocalDensity.current
    val palette = rememberArtworkPalette(track.artworkUrl)
    val size = pose.size
    val spine = size * 0.12f                    // 书脊宽（主题色 + 横排歌名）
    val page = size * 0.18f                     // 页块厚（打开时露出的背块）
    val offX = pose.x - size / 2f
    val offY = pose.y - size / 2f - pose.lift
    val camDist = 12f * density.density
    val spineBrush = Brush.verticalGradient(
        listOf(
            lerp(palette.dominant, Color.White, 0.16f),
            palette.dominant,
            lerp(palette.dominant, Color.Black, 0.45f)
        )
    )

    // —— 外层：世界姿态（整本书唯一的整体旋转）——
    Box(
        Modifier
            .offset { IntOffset(offX.roundToInt(), offY.roundToInt()) }
            .size(with(density) { size.toDp() })
            .zIndex(z)
            .graphicsLayer {
                rotationY = pose.rotationY
                rotationX = pose.rotationX
                transformOrigin = TransformOrigin(0.5f, 0.5f)
                cameraDistance = camDist
                this.alpha = pose.alpha
            }
    ) {
        if (openness <= 0.02f) {
            // — 合拢：一张封面 + 右缘书脊 —
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { spine.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        rotationY = -90f
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = camDist
                    }
                    .background(spineBrush),
                contentAlignment = Alignment.Center
            ) { SpineTitle(track, size) }
        } else {
            // — 打开：封面自右缘向前翻 + 页块 + 书脊缝 —
            // 页块（背块，主题色，略向后张）
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(with(density) { page.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        rotationY = -20f * openness
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = camDist
                    }
                    .background(spineBrush)
            )
            // 书脊缝（右侧、封面与页块之间的缝 + 横排歌名）
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = with(density) { page.toDp() })
                    .width(with(density) { spine.toDp() })
                    .fillMaxHeight()
                    .graphicsLayer {
                        rotationY = 90f * openness
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        cameraDistance = camDist
                    }
                    .background(spineBrush),
                contentAlignment = Alignment.Center
            ) { SpineTitle(track, size) }
            // 封面：绕右缘向前翻
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = track.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = -32f * openness
                        transformOrigin = TransformOrigin(1f, 0.5f)
                        cameraDistance = camDist
                    }
            )
        }
    }
}

/** 书脊上的横排歌名（参照参考图：歌名横着写在书脊高的一侧） */
@Composable
private fun SpineTitle(track: Track, size: Float) {
    val density = LocalDensity.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = track.title,
            color = Color.White.copy(alpha = 0.94f),
            fontSize = with(density) { (size * 0.052f).toDp() }.value.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
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