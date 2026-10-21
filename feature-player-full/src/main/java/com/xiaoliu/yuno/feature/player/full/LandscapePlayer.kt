package com.xiaoliu.yuno.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.ui.component.LyricFont
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.AlbumBlurBackground
import com.xiaoliu.yuno.feature.lyrics.render.LyricWaitingDots
import com.xiaoliu.yuno.feature.player.KaraokeSingLine
import com.xiaoliu.yuno.core.ui.component.ArtworkPalette
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.LocalHazePerformanceMode
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
        // 背景：静态颜料混色（再压暗一点，营造氛围）。
        // 耗电优化：横屏全屏里颜料流动动画停摆 —— 歌词滚筒/封面切换自带动态，
        // 流动画层白跑；静态时观感不变（色团仍在，只是不漂移）
        AlbumBlurBackground(
            artworkUrl = track?.artworkUrl,
            palette = palette,
            modifier = Modifier.fillMaxSize(),
            scrimAlpha = 0.42f,
            animated = false
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

// ============================================================================
// 滚筒歌词：当前行最大（左侧原点放大、向左延伸），已唱行越往上越小，
// 未唱行更小更暗；行切换用 430ms 连续动画，而不是瞬间直切。
// ============================================================================

// ── 横屏滚筒歌词的排版常量（真机规格 Beta5）──────────────
/** 当前行缩放 */
private const val CURRENT_LINE_SCALE = 1.14f
/**
 * 下一句缩放 —— 真机确认这个大小合适，**不要动**。
 * 下下句 = 本值 × 0.9，之后每行再 ×0.9（几何衰减）。
 */
private const val NEXT_LINE_SCALE = 0.88f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WheelLyrics(
    timeline: LyricsTimeline,
    positionMs: State<Long>,
    onLineClick: ((com.xiaoliu.yuno.core.model.LyricLine) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val lines = remember(timeline) { timeline.allLines() }
    val currentIndex by remember(timeline) {
        derivedStateOf { timeline.indexOfLineAt(positionMs.value) }
    }
    // 歌词空档（前奏/长间奏）→ 显示三个逐次渐亮的等待球
    val waitingForLyrics by remember(timeline) {
        derivedStateOf { timeline.isInGap(positionMs.value) }
    }
    val floatIndex = remember { Animatable(0f) }

    // 换歌时直接归位：否则会从上首歌的行号"滚"回新歌第 0 行，非常生硬
    LaunchedEffect(timeline) {
        floatIndex.snapTo(0f)
    }

    // 行切换（Beta5 改善滚动动画）：
    //   旧法 520ms + FastOutSlowIn —— 仍偏"直切"，看得出是两行之间跳。
    //   现在改成 **620ms + 更平缓的长尾缓出**（前 40% 走完 70% 位移），
    //   加上 floatIndex 本身是连续量（按行偏移插值），视觉上是"滚筒连续转"
    //   而不是"闪一下换一行"。
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            floatIndex.animateTo(
                targetValue = currentIndex.toFloat(),
                animationSpec = tween(
                    620,
                    easing = CubicBezierEasing(0.16f, 1f, 0.30f, 1f)
                )
            )
        }
    }

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val baseY = with(density) { 92.dp.toPx() }      // 当前行的顶部（上方留出已唱行）
        // 真机反馈（全屏歌词）：已唱/当前/下一句之间的行间距再放大一点
        val rowGap = with(density) { 24.dp.toPx() }     // 行与行之间的固定间隙
        val textWidthPx = with(density) { (maxWidth * 0.87f).toPx() }.toInt()

        // 折行后的"真实行高"：必须按实测高度排版，否则长短句会挤在一起
        // （真机截图：折行的行与上下行重叠成一团）
        // 统一用同一 style/maxLines 测量：避免切换时行高重排引起跳动
        val heights = remember(lines, textWidthPx) {
            lines.map { line ->
                measurer.measure(
                    text = AnnotatedString(line.plainText),
                    style = wheelStyle().copy(fontFamily = LyricFont.currentFamily()),
                    constraints = Constraints(maxWidth = textWidthPx),
                    maxLines = 3
                ).size.height.toFloat()
            }
        }
        // 每行顶部的累计偏移（前缀和）
        val offsets = remember(heights) {
            val arr = FloatArray(heights.size + 1)
            for (i in heights.indices) arr[i + 1] = arr[i] + heights[i] + rowGap
            arr
        }

        val cur = floatIndex.value
        // ⚠️ 攻击性检查（Beta14）：`coerceIn(0, lines.lastIndex)` 在**空列表**时
        //    会抛 IllegalArgumentException（Cannot coerce value to an empty range）。
        //    换歌瞬间 timeline 可能短暂为空，这里必须挡一下。
        if (lines.isEmpty()) return@BoxWithConstraints
        val lo = cur.toInt().coerceIn(0, lines.lastIndex)
        val hi = (lo + 1).coerceAtMost(lines.lastIndex)
        val frac = (cur - lo).coerceIn(0f, 1f)
        // 当前滚动位置（行偏移的连续插值）→ 让当前行顶部落在 baseY
        val roll = offsets[lo] + (offsets[hi] - offsets[lo]) * frac

        val centerRow = cur.roundToInt().coerceIn(0, lines.lastIndex)
        val from = (centerRow - 4).coerceAtLeast(0)
        val to = (centerRow + 4).coerceAtMost(lines.lastIndex)

        if (waitingForLyrics && lines.isNotEmpty()) {
            // 等待球（叠加层，不遮挡歌词）：横屏镜像竖屏的真机规格 ——
            // 球只待在**行与行之间的空当**，绝不像旧版那样插进句子里。
            //   · 前奏（currentIndex < 0）：第一句还没滚进来，放第一行上方的
            //     空白带（baseY 半高处）；第一句滚上来时 isInGap 切 false，球消失
            //   · 间奏：夹在当前句与下一句之间的行距中间（行距 24dp、药丸 33dp，
            //     只轻微贴边），随滚筒滚动
            val dotsH = with(density) { 52.dp.toPx() }
            val dotsY = if (currentIndex < 0) {
                baseY / 2f - dotsH / 2f
            } else {
                val row = currentIndex.coerceIn(0, lines.lastIndex)
                (baseY + offsets[row] - roll) + heights[row] + rowGap / 2f - dotsH / 2f
            }
            Box(
                Modifier.offset { IntOffset(0, dotsY.roundToInt()) }
            ) {
                LyricWaitingDots()
            }
        }

        // 歌词**永远渲染**（真机 bug Beta5）：
        //   旧法 `if (waitingForLyrics) { 等待球 } else { 歌词 }` ——
        //   前奏时一行歌词都不画，前奏一结束全部涌出来 = "闪现一下所有歌词都加载出来"。
        //   现在歌词常驻，等待球是**叠加层**，互不影响。
        run {
            for (i in from..to) {
                val d = i - cur
                val absD = abs(d)
                if (absD > 4.2f) continue

                // 缩放（真机规格 Beta5）：
                //   · 当前行 1.14
                //   · **下一句 = 0.88（真机确认合适）**
                //   · **下下句 = 下一句 × 0.9**，之后每行再 ×0.9（几何衰减）
                // 旧法是线性衰减 `1.14 - 0.30×|d|`，d=2 就掉到 0.567
                // —— 相对下一句只剩 0.64 倍，看着像"一堆小虫子"。
                // 几何衰减让远处的行温和收小，不崩塌。
                val perRow = if (d > 0f) 0.9f else 0.92f
                val steps = (absD.toInt() - 1).coerceAtLeast(0)
                var sized = NEXT_LINE_SCALE
                repeat(steps) { sized *= perRow }
                val scale = sized.coerceAtLeast(0.55f)
                val nextScale = scale
            // 亮度（真机：上下行太亮，快赶上主歌词了）：
            // 已唱行压到 0.62 起、未唱行再额外压一层；当前行保持 1
            val extraDim = 1f - 0.20f * d.coerceIn(0f, 1f)
            val alpha = ((1f - 0.55f * absD) * extraDim).coerceIn(0.05f, 1f)
            // 未唱行：毛玻璃模糊。模糊半径直接画在同一个 graphicsLayer 里
            // （RenderEffect），不再叠 Modifier.blur 的独立图层——
            // 之前"凭空蹦出来"就是双层 layer 叠加导致的。
            val blurPx = if (d > 0.55f && Build.VERSION.SDK_INT >= 31) {
                with(density) {
                    (6f + (d - 1f).coerceIn(0f, 1f) * 3f).dp.toPx()
                }
            } else 0f

            // 行级变换（缩放/亮度/模糊 + 点击）：逐字行与纯文本行共用，
            // 两种渲染在同一套滚筒几何下完全对齐
            val lineTransform = Modifier
                .offset {
                    IntOffset(
                        0,
                        (baseY + offsets[i] - roll).roundToInt()
                    )
                }
                .graphicsLayer {
                    scaleX = nextScale
                    scaleY = nextScale
                    // 左侧为原点放大：当前行向左延伸，但不压到专辑图
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    this.alpha = alpha
                    // 单一图层内做模糊（未唱行）：
                    // 原生 RenderEffect 经 asComposeRenderEffect() 桥接进 graphicsLayer，
                    // 不另开 layer，不会像 Modifier.blur 那样多叠一层导致"凭空蹦出来"
                    if (Build.VERSION.SDK_INT >= 31 && blurPx > 0f) {
                        renderEffect =
                            android.graphics.RenderEffect
                                .createBlurEffect(blurPx, blurPx, Shader.TileMode.DECAL)
                                .asComposeRenderEffect()
                    }
                }
                .pointerInput(i) {
                    detectTapGestures { onLineClick?.invoke(lines[i]) }
                }

            // 当前句 + 有字级时间戳 → 逐字卡拉 OK（真机需求：横屏也要逐字）；
            // 无逐字的行、背景和声行、非当前句 → 照旧纯文本
            val line = lines[i]
            if (i == currentIndex && !line.isLineLevel && !line.isBackground) {
                KaraokeSingLine(
                    line = line,
                    positionMs = positionMs,
                    style = wheelStyle().copy(fontFamily = LyricFont.currentFamily()),
                    maxWidthPx = textWidthPx,
                    maxLines = 3,
                    modifier = lineTransform
                )
            } else {
                Text(
                    text = line.plainText,
                    color = Color.White,
                    fontSize = 36.sp,
                    fontFamily = LyricFont.currentFamily(),
                    // 折行行距：同句折成两行时，中间多留 34px ≈ 12dp
                    lineHeight = 56.sp,
                    // 统一字重：粗细不是可动画属性，切换时会突变
                    fontWeight = FontWeight.Medium,
                    // 一行放不下必须折行；宽度与实测时一致，放大后不溢出屏幕
                    softWrap = true,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth(0.87f)
                        .then(lineTransform)
                )
            }
            }
        }
    }
}

/** 滚筒行样式（测量与渲染共用，保证折行位置一致） */
private fun wheelStyle() = TextStyle(
    fontSize = 36.sp,
    // 与渲染一致：折行时中间多留一截（34px ≈ 12dp）
    lineHeight = 56.sp,
    fontWeight = FontWeight.Medium
)
