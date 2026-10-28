package com.xiaoliu.yuno.feature.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.AudioBandsHub
import com.xiaoliu.yuno.core.ui.component.FlowingPaletteBackground
import com.xiaoliu.yuno.core.ui.component.GlassIconButton
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import com.xiaoliu.yuno.feature.lyrics.fx.FxWebView
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay

/**
 * 全屏特效页（Folia WebView 版）。
 *
 * 形态：横屏全屏，跟 [LandscapePlayerOverlay] 同规格；
 * 内容：Folia 原版 React + framer-motion 特效（WebView 加载 bundle.js），
 *  Kotlin 侧通过 YunoFeed bridge 喂歌词进度和模式配置。
 *
 * UI 外壳（2026-10-28 用户钦定）：
 *   · 顶部：液态玻璃胶囊迷你控制器——进入页面从上方弹入（弹簧），
 *     2.75s 后自动上收隐藏；内容照搬主页迷你控制器（封面/歌名/歌手/启停/下一曲/进度线），
 *     尺寸按比例缩小
 *   · 左侧：圆形液态玻璃退出钮（68dp，与底栏搜索钮同款工艺）
 *   · 玻璃采样源 = 封面模糊背景层（WebView 是 AndroidView，不参与 Haze 采样）
 */
@Composable
fun FxLandscapeOverlay(
    vm: PlayerViewModel,
    mode: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val track: Track? by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying: Boolean by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val palette = rememberArtworkPalette(track?.artworkUrl)
    val timeline = remember(lyrics) { lyrics?.let { LyricsTimeline(it.lines) } }
    val hazeState = rememberHazeState()

    // ★ 亮封面保护（2026-10-28 2.6.5）：封面很亮时，Folia 背景跟着亮，
    //   白字和背景糊在一起看不清。按封面加权亮度把背景层压透（透出下面的深色底），
    //   深色封面不受影响（1.0f 原样）。用户反馈原话：字跟背景混到一起，颜色一样亮度一样。
    val coverLuma = remember(palette) {
        val sw = palette.swatches
        if (sw.isEmpty()) palette.dominant.luminance()
        else sw.sumOf { (it.color.luminance() * it.weight).toDouble() }.toFloat()
    }
    // ★ 背景亮度策略（2026-10-28 2.6.10，用户反馈"背景太亮、歌词看不清"）。
    // Folia 的背景是三层叠的：
    //   ① FluidBackground  —— 用封面色画的流动背景（截图里那片奶白就是它）
    //   ② 一层 theme.backgroundColor 的纯色遮罩，opacity = common.opacity（默认 0.75）
    //   ③ GeometricBackground —— 几何图形 + 暗角
    // 之前把 theme.backgroundColor 设成了 transparent → 第②层直接失效 → 封面色原样透出，
    // 于是背景亮到把白字吃掉。现在：
    //   luma ≤ 0.28          → 遮罩 0.80（封面色还透一点，不至于死黑）
    //   0.28 < luma ≤ 0.52   → 遮罩 0.80 → 0.94 线性加重
    //   luma > 0.52（偏白系）→ **反转**：不用封面色背景，直接深灰黑底（用户钦定：白色系朝黑色反转）
    // 只作用于**全屏特效页**，不碰我们自己的流动背景、也不碰全屏播放的流动背景。
    val bgInvert = coverLuma > 0.52f
    val bgScrim = if (bgInvert) {
        1f
    } else {
        (0.80f + ((coverLuma - 0.28f) / 0.24f).coerceIn(0f, 1f) * 0.14f).coerceAtMost(0.94f)
    }

    // 进入强制横屏（跟 LandscapePlayerOverlay 同规格）
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

    // 任意触碰重新弹出（用户钦定 2026-10-28 2.6.2-r）：
    // 隐藏后一旦检测到触摸（含 WebView 内的滑动/点击），就重置 2.75s 计时器并弹回顶部。
    // 主容器加 pointerInput 捕捉层：WebView 不消费触摸事件时自动落到这层，
    // 每次 DOWN 把 touchTrigger +1 → FxTopChrome 的 LaunchedEffect key 变化 → 重新弹入。
    val touchTrigger = remember { mutableStateOf(0) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    // requireUnconsumed = false 是关键：WebView 是子节点，它的 onTouchEvent
                    // 返回 true 会把 DOWN 标成已消费，父级用默认值（true）就永远收不到。
                    awaitFirstDown(requireUnconsumed = false)
                    touchTrigger.value = (touchTrigger.value + 1) % 1000 // 单槽信号
                    // 不主动 consume：让事件继续传给 WebView
                }
            }
    ) {
        // ——— 玻璃采样层：面积加权流动背景（Compose 内容，可被 Haze 采集）———
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            // ★ 性能（2026-10-28 2.6.4）：这层**不再动画**。
            //   Folia 的共享 backdrop（classic/claddagh/diorama）或模式自带背景（花火/光影/摆钟）
            //   已经把整屏盖住了，这里再跑一层会动的全屏模糊渐变 = 白烧 GPU。
            //   保留本层只为给上面的液态玻璃胶囊/圆球提供 Haze 采样源（静态画一次）。
            FlowingPaletteBackground(
                palette = palette,
                modifier = Modifier.fillMaxSize(),
                animated = false,
                speedFactor = 1.0f,
                scrimAlpha = 0.34f
            )
        }

        // ——— Folia 原版 React 特效（WebView 加载 Folia 源码构建产物）———
        FxWebView(
            timeline = timeline,
            positionFlow = vm.positionMsFlow,
            mode = mode,
            songTitle = track?.title.orEmpty(),
            songArtist = track?.artist.orEmpty(),
            songId = track?.id.orEmpty(),
            durationMs = track?.durationMs ?: 0L,
            isPlaying = isPlaying,
            glowColor = palette.vibrant,
            coverUrl = track?.artworkUrl.orEmpty(),
            bandsFlow = AudioBandsHub.bands,
            bgScrim = bgScrim,
            bgInvert = bgInvert,
            modifier = Modifier.fillMaxSize()
        )

        // ——— 顶部 UI（胶囊迷你控制器 + 左上角退出圆球）———
        // 一起弹入 → 停留 2.75s → 一起上收隐藏（用户钦定：退出钮跟着迷你控制器一起收回）；
        // 任意触摸 → touchTrigger +1 → 重新弹入（2.6.2-r）
        FxTopChrome(
            vm = vm,
            hazeState = hazeState,
            onExit = onExit,
            touchTrigger = touchTrigger.value,
            modifier = Modifier.fillMaxSize()
        )
    }

    // 特效页开着时才做音频频谱分析（没人看不算，省电）
    DisposableEffect(Unit) {
        AudioBandsHub.setListenerActive(true)
        onDispose { AudioBandsHub.setListenerActive(false) }
    }
}

// ============================================================================
// 顶部 UI 组：胶囊迷你控制器 + 左上角退出圆球（同步弹入/收回）
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun FxTopChrome(
    vm: PlayerViewModel,
    hazeState: HazeState,
    onExit: () -> Unit,
    touchTrigger: Int = 0,
    modifier: Modifier = Modifier
) {
    // slide: -1.4 = 屏幕外（上方），0 = 到位
    val slide = remember { Animatable(-1.4f) }
    // 任意触碰后重新弹出（用户钦定 2026-10-28 2.6.2-r）：
    // 隐藏后一旦检测到触摸（含 WebView 内的滑动/点击），就重置 2.75s 计时器并弹回顶部。
    // touchTrigger 由主容器的 pointerInput 捕捉层喂进来（每次 DOWN +1），
    // key 变化 → LaunchedEffect 重跑 → 重新弹入。
    LaunchedEffect(touchTrigger) {
        // 首次进入或新触摸：弹入 → 停留 2.75s → 隐藏
        slide.animateTo(
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
        )
        delay(2750L)
        slide.animateTo(
            targetValue = -1.4f,
            animationSpec = spring(dampingRatio = 1f, stiffness = 320f)
        )
    }

    Box(
        modifier.graphicsLayer {
            translationY = slide.value * 96.dp.toPx()
            alpha = (1f + slide.value * 0.75f).coerceIn(0f, 1f)
        }
    ) {
        // 左上角退出圆球（44dp，同底栏搜索钮的液态玻璃工艺）
        GlassIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onExit,
            size = 44.dp,
            iconSize = 20.dp,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 16.dp, top = 16.dp)
        )

        // 顶部居中胶囊迷你控制器
        FxMiniCapsule(
            vm = vm,
            hazeState = hazeState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )
    }
}

// ============================================================================
// 顶部胶囊迷你控制器（主页 MiniPlayer 的等比例缩小版）
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun FxMiniCapsule(
    vm: PlayerViewModel,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val track by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)
    val isPlaying by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)
    val t = track ?: return

    // R 角 26dp：与主页迷你条同一档圆润度
    val capsule = RoundedCornerShape(26.dp)
    val style = rememberLiquidGlassStyle(shape = capsule)

    Box(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            Modifier
                .widthIn(max = 420.dp)
                .height(56.dp)
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = style
                )
                .clip(capsule)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = t.artworkUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = t.title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = t.artist,
                        color = YunoTextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 启停（右）
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { vm.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                // 下一曲
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { vm.next() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FastForward,
                        contentDescription = "下一首",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // 微缩进度线（贴胶囊底边，跟主页迷你条同款）
            FxMiniProgressLine(
                vm = vm,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
            )
        }
    }
}

/** 2dp 微缩进度线：只订阅 50ms 进度流，胶囊其余部分不重组 */
@Composable
private fun FxMiniProgressLine(
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