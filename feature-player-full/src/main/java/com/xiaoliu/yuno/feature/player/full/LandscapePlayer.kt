package com.xiaoliu.yuno.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.FlowingPaletteBackground
import com.xiaoliu.yuno.feature.player.WheelLyrics
import com.xiaoliu.yuno.core.ui.component.ArtworkPalette
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import dev.chrisbanes.haze.ExperimentalHazeApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 横屏全屏播放模式 —— 第二轮重做（iPod Cover Flow 式）
//
// 真机需求：
//   · 专辑封面「圆着斜过来」：当前封面居中发扩散光，左右是队列里的前后封面，
//     暗淡、带反光质感（反射当前封面散发的漫反射光）
//   · 左右滑动切歌（图跟着手走，歌跟着切）
//   · 双击封面 = 暂停（封面中央出现暂停标记）；暂停时单击 = 播放
//   · 去掉底部上一首/播放/下一首控件，去掉右上角 ×（全面屏手势返回退出）
//   · 歌词改成滚筒：当前行最大（向左延伸、不压封面），已唱行越往上越小，
//     未唱行小且暗；切换用连续动画，不再生硬直切
//   · 切歌时歌词上方短暂显示 2 秒「歌名 / 艺人」
//   · 除当前封面和当前歌词外，整体压暗一层灰黑蒙版（暗淡，不是死黑）
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
fun LandscapePlayerOverlay(
    vm: PlayerViewModel,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)
    val queue by vm.queueFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val queueIndex by vm.queueIndexFlow.collectAsStateWithLifecycle(initialValue = 0)
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val timeline = remember(lyrics) { lyrics?.let { LyricsTimeline(it.lines) } }
    val palette = rememberArtworkPalette(track?.artworkUrl)
    val positionState: State<Long> =
        vm.positionMsFlow.collectAsStateWithLifecycle(initialValue = 0L)

    // 进入时强制横屏，退出恢复竖屏；同时锁屏（真机需求：全屏歌词页不能自动熄屏）
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.run {
            runCatching {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.run {
                runCatching {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // 切歌时「歌名 / 艺人」短暂显示 2 秒
    var showNowPlaying by remember { mutableStateOf(true) }
    LaunchedEffect(queueIndex, track?.id) {
        showNowPlaying = true
        delay(2000)
        showNowPlaying = false
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 背景：面积加权颜料流动（2026-10-28 用户钦定）——
        // 封面上每一种颜色都按原图面积占比出现在背景里，大色块更突出，
        // Screen 混合出迷幻的混色；Lissajous 随机洋流（2.6.1-r，慢速）
        FlowingPaletteBackground(
            palette = palette,
            modifier = Modifier.fillMaxSize(),
            animated = true,
            speedFactor = 1.0f,
            scrimAlpha = 0.42f
        )

        // 全屏灰黑蒙版：除了当前封面与当前歌词，别处都被压下去（暗淡不是死黑）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
        )

        Row(Modifier.fillMaxSize()) {
            // —— 左半屏：Cover Flow ——
            CoverFlowArea(
                vm = vm,
                tracks = queue,
                index = queueIndex,
                palette = palette,
                isPlaying = isPlaying,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            )

            // —— 右半屏：滚筒歌词 ——
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                Box(
                    Modifier.fillMaxSize()
                ) {
                    if (timeline != null && timeline.size > 0) {
                        WheelLyrics(
                            timeline = timeline,
                            positionMs = positionState,
                            onLineClick = { line -> vm.seekTo(line.startMs) },
                            // 歌词区右移、用满右半屏（真机：之前贴着中线太偏左）
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 56.dp, end = 16.dp)
                        )
                    }
                }

                // （毛玻璃层移到最外层 Box —— 与 MainShell 的结构一致，
                //   source 与 blur 必须在不同层级，否则 Haze 采样不到内容）

                // 切歌标题：歌词上方（原 × 的位置左方），2 秒后淡出
                androidx.compose.animation.AnimatedVisibility(
                    visible = showNowPlaying,
                    enter = fadeIn(),
                    exit = fadeOut(tween(600)),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 12.dp, top = 10.dp)
                ) {
                    Column {
                        Text(
                            text = track?.title.orEmpty(),
                            color = Color.White,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track?.artist.orEmpty(),
                            color = Color.White.copy(alpha = 0.62f),
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // （不再使用 Haze 覆盖层：它在横屏这个布局里采样失败会画出黑块，
        //  未唱行的"毛玻璃"改为行级 blur，见 WheelLyrics）
    }
}

// ============================================================================
// Cover Flow：中间当前封面 + 左右后景封面（暗淡、倾斜、反光）
// ============================================================================

@Composable
private fun CoverFlowArea(
    vm: PlayerViewModel,
    tracks: List<Track>,
    index: Int,
    palette: ArtworkPalette,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // 浮点索引（真机要求）：拖动时跟手，松手后动画到相邻槽位。
    // 每张封面按「自己相对浮点索引的槽位」插值位置/缩放/倾斜/透明度 ——
    // 于是切歌是"当前压到左侧斜着、右侧转正到中间、右二补上"的轮换，
    // 而不是整排图片一起平移。
    val floatIndex = remember { Animatable(index.toFloat()) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(index) {
        // index 变化 → floatIndex 平滑滑到新槽位：
        //   · 手动切歌：手势动画已把 floatIndex 推到同一目标（差值≈0），不打断
        //   · 自然播完切歌（真机反馈"闪现下一首"）：差 1 个槽位 →
        //     新封面顺滑滑过来转正（animateTo，不再 snapTo 瞬跳）
        val target = index.toFloat()
        if (abs(floatIndex.value - target) > 0.01f) {
            floatIndex.animateTo(target, tween(520, easing = FastOutSlowInEasing))
        }
    }

    BoxWithConstraints(modifier) {
        val coverSize: Dp = minOf(maxWidth * 0.62f, maxHeight * 0.62f)
        val coverPx = with(density) { coverSize.toPx() }
        val centerX = with(density) { maxWidth.toPx() } / 2f
        val centerY = with(density) { maxHeight.toPx() } / 2f

        val current = tracks.getOrNull(index)

        // —— 围着封面一圈的「灯带」发光（真机：之前做成了太阳式光晕，要贴边的一圈）——
        // 做法：封面后放一块略大的圆角矩形，填充专辑主题色的微渐变，重度模糊；
        // 封面盖住中心，只露出沿边缘的一圈柔光带。
        val glowPad = with(density) { 10.dp.toPx() }
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (centerX - coverPx / 2f - glowPad).roundToInt(),
                        (centerY - coverPx / 2f - glowPad).roundToInt()
                    )
                }
                .size(coverSize + 20.dp)
                // Unbounded：允许模糊光扩散出组件边界，
                // 否则会被裁剪成"方盒子里的光"（真机反馈）
                .blur(30.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            palette.vibrant.copy(alpha = 0.85f),
                            palette.dominant.copy(alpha = 0.60f),
                            palette.light.copy(alpha = 0.70f),
                            palette.vibrant.copy(alpha = 0.85f)
                        )
                    ),
                    shape = RoundedCornerShape(22.dp)
                )
        )

        // —— 滑动切歌手势层：覆盖整个封面区域（向左拖 = 下一首）——
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(index, coverPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragAccum = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragAccum += amount
                            val frac = (-dragAccum / (coverPx * 0.85f)).coerceIn(-1f, 1f)
                            scope.launch { floatIndex.snapTo(index + frac) }
                        },
                        onDragEnd = {
                            scope.launch {
                                val frac = floatIndex.value - index
                                when {
                                    frac <= -0.28f -> {
                                        // 槽位轮换：右侧那张转正到中间，当前这张压到左侧斜着
                                        floatIndex.animateTo(
                                            index - 1f,
                                            tween(300, easing = FastOutSlowInEasing)
                                        )
                                        vm.previous()
                                    }
                                    frac >= 0.28f -> {
                                        floatIndex.animateTo(
                                            index + 1f,
                                            tween(300, easing = FastOutSlowInEasing)
                                        )
                                        vm.next()
                                    }
                                    else -> floatIndex.animateTo(
                                        index.toFloat(),
                                        spring(
                                            dampingRatio = 0.82f,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                }
                                dragAccum = 0f
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                floatIndex.animateTo(index.toFloat(), tween(220))
                            }
                        }
                    )
                }
        )

        // —— 槽位轮换渲染：当前 + 左右各两张 ——
        val base = floatIndex.value
        val from = (base.toInt() - 2).coerceAtLeast(0)
        val to = (base.toInt() + 2).coerceAtMost(tracks.lastIndex)
        for (i in from..to) {
            val t = tracks.getOrNull(i) ?: continue
            val slot = i - base
            FlowCover(
                track = t,
                slot = slot,
                coverSize = coverSize,
                coverPx = coverPx,
                centerX = centerX,
                centerY = centerY,
                glowColor = palette.vibrant,
                isCenter = abs(slot) < 0.5f,
                onTap = { if (!isPlaying) vm.togglePlayPause() },
                onDoubleTap = { if (isPlaying) vm.togglePlayPause() }
            )
        }

        // 暂停标记：暂停时出现在封面中央（单击封面恢复播放）
        if (!isPlaying) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (centerX - coverPx / 2f).roundToInt(),
                            (centerY - coverPx / 2f).roundToInt()
                        )
                    }
                    .size(coverSize),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = "已暂停，点按继续",
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(coverSize * 0.28f)
                )
            }
        }
    }
}

/**
 * 槽位封面：slot = 相对当前浮点索引的位置
 *   0 = 正中；-1 = 左侧斜着（刚被挤下去的歌）；+1 = 右侧斜着（即将转到中间）；
 *   ±2 = 屏幕外、正在补位（淡出/淡入）。
 * 位置、缩放、倾斜、透明度全部按 slot 连续插值 —— 切歌时每张图在自己的槽位之间
 * 轮换，而不是整排一起平移。
 */
@Composable
private fun FlowCover(
    track: Track,
    slot: Float,
    coverSize: Dp,
    coverPx: Float,
    centerX: Float,
    centerY: Float,
    glowColor: Color,
    isCenter: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit
) {
    val density = LocalDensity.current
    val absSlot = abs(slot).coerceAtMost(2.2f)
    val dir = if (slot >= 0f) 1f else -1f
    val near = absSlot.coerceAtMost(1f)          // 0 → 1（中间 → 相邻侧位）
    val far = (absSlot - 1f).coerceAtLeast(0f)   // 0 → 1（侧位 → 屏幕外）

    // 梯形排列：±1 斜靠在两侧，±2 滑出屏幕并淡出
    val x = centerX - coverPx / 2f + dir * coverPx * 0.55f * absSlot.coerceAtMost(2.05f)
    val scale = (1f - 0.22f * near - 0.10f * far).coerceAtLeast(0.55f)
    val rotation = -dir * 36f * near
    val alpha = (1f - 0.60f * near - 0.45f * far).coerceIn(0f, 1f)
    if (alpha <= 0.01f) return

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), (centerY - coverPx / 2f).roundToInt()) }
            .size(coverSize)
            .zIndex(1f - near)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                rotationY = rotation
                cameraDistance = 14f * this.density
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (isCenter) Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onTap = { onTap() }
                    )
                } else Modifier
            )
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        // 反光质感：一层来自当前封面的漫射色 + 斜向玻璃高光
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.20f * alpha),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.06f * alpha)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(coverPx, coverPx)
                    )
                )
        )
    }
}
