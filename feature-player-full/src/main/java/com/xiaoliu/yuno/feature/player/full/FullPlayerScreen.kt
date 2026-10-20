package com.xiaoliu.yuno.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.model.RepeatMode
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.AlbumBlurBackground
import com.xiaoliu.yuno.core.ui.component.ArtworkPalette
import com.xiaoliu.yuno.core.ui.component.GlassIconButton
import com.xiaoliu.yuno.core.ui.component.TrackRow
import com.xiaoliu.yuno.core.ui.component.boostSaturation
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

// ============================================================================
// 全屏播放页 —— 真机反馈第四轮改版：
//   · 顶部「封面/歌曲名/歌手」常驻不滚；背后是毛玻璃（hazeBlur + 垂直渐进强度），
//     歌词向上滚动时被逐渐模糊、顺滑"没入"顶部区域（没有硬切线）
//   · 歌词全屏延伸（直到屏幕物理顶部，控制栏玻璃后面也透着歌词）
//   · 进度胶囊 + 控制栏整体上移（真机反馈 26-28px）
//   · 全局液态玻璃更通透 + 更多折射变形 + 边缘色散（见 LiquidGlass.kt）
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
fun FullPlayerScreen(
    vm: PlayerViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track by vm.trackFlow.collectAsStateWithLifecycle(initialValue = null)

    // 进入播放页隐藏状态栏（沉浸式，真机要求）；离开恢复显示
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    hide(WindowInsetsCompat.Type.statusBars())
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        DisposableEffect(Unit) {
            onDispose {
                (view.context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view)
                        .show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        // 歌词页锁屏（真机需求：播放页/全屏歌词页不能自动熄屏）
        DisposableEffect(Unit) {
            val activity = view.context as? Activity
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // 播放页两种模式：歌词（默认）/ 无歌词大封面（真机要求）；队列面板开关
    var showLyrics by remember { mutableStateOf(true) }
    var showQueue by remember { mutableStateOf(false) }
    // 横屏全屏播放（三个点菜单进入）
    var showFullscreen by remember { mutableStateOf(false) }
    // 控制栏（进度条 + 控制板）自动渐隐：真机需求 —— 1.75s 无操作就淡出，
    // 一碰屏幕立刻回来；翻歌词 / 点选歌词期间不算"无操作"，保持可见。
    var controlsVisible by remember { mutableStateOf(true) }
    // 每次触摸/操作自增一次 → 重新开始 1.75s 空闲倒计时
    var interactionTick by remember { mutableIntStateOf(0) }
    // 「手势锁」：最近一次被判为**滑动**的时间戳。
    // 滑动之后短时间内到达的歌词点击一律丢弃 —— 防误触跳歌的关键。
    var swipeGuardMs by remember { mutableStateOf(0L) }

    // 进入时记录状态栏真实高度：隐藏后系统 insets 归零，标题栏用记录值钉回原位
    val statusBarTopPx = remember {
        ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }
    val statusBarTopDp = with(LocalDensity.current) { statusBarTopPx.toDp() }
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsLoading by vm.lyricsLoading.collectAsStateWithLifecycle()
    val palette = rememberArtworkPalette(track?.artworkUrl)
    val timeline = remember(lyrics) { lyrics?.let { LyricsTimeline(it.lines) } }

    // 进度以 State<Long> 下传 —— LyricsView 内部"谁读谁重组"，只有当前行吃这口高频流
    val positionState: State<Long> = vm.positionMsFlow.collectAsStateWithLifecycle(initialValue = 0L)

    val hazeState = rememberHazeState()

    // 歌词聚焦（长按触发）：动画进度提升到这一层 —— 歌词列表的"动态缩小"与
    // 聚焦特效共用同一进度源，保证完全同步
    val focusProgress = remember { Animatable(0f) }
    var focusRequest by remember { mutableStateOf<LyricFocusRequest?>(null) }
    var overlayRequest by remember { mutableStateOf<LyricFocusRequest?>(null) }
    var topBarBottomPx by remember { mutableFloatStateOf(0f) }
    var topGlassOrigin by remember { mutableStateOf(Offset.Zero) }
    var favBtnRect by remember { mutableStateOf<Rect?>(null) }
    var moreBtnRect by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(focusRequest) {
        val req = focusRequest
        if (req != null) {
            overlayRequest = req
            focusProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(1200, easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f))
            )
        } else if (overlayRequest != null) {
            focusProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(430, easing = FastOutLinearInEasing)
            )
            overlayRequest = null
        }
    }

    // 系统返回：全屏模式先退出全屏 → 聚焦态退出聚焦 → 收起播放页
    BackHandler {
        when {
            showFullscreen -> showFullscreen = false
            focusRequest != null -> focusRequest = null
            else -> onClose()
        }
    }

    // 顶部常驻毛玻璃（真机反馈：这里要"毛玻璃"，不是液态玻璃）——
    // 模糊强度自顶向下渐变到 0：歌词滚上来时被"渐冻"进顶部区域，边缘无硬切
    // 顶部毛玻璃：整条保留（不整片透明）；只在两个按钮的圆形区域"挖孔"，
    // 让按钮不受毛玻璃影响、直接折射底下的歌词（AGSL 双圆孔遮罩，Android 13+）
    val favRect = favBtnRect

    val moreRect = moreBtnRect
    val topGlass = remember(favRect, moreRect, topGlassOrigin) {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(32.dp)
            noiseFactor(0.02f)
            backgroundColor(Color(0x12000000))
            progressive(
                HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                favRect != null && moreRect != null
            ) {
                // 洞半径比按钮半径小 1px：超出一个像素都不能
                val radius = minOf(favRect.width, favRect.height) / 2f - 1f
                mask(
                    ButtonHolesMask(
                        holeA = favRect.center - topGlassOrigin,
                        holeB = moreRect.center - topGlassOrigin,
                        radius = radius
                    )
                )
            }
        }
    }

    // 底部渐变毛玻璃（与顶部标题栏同工艺，方向相反：底部最强、向上渐隐）
    val bottomGlass = remember {
        HazeBlurStyle {
            blurEnabled(true)
            blurRadius(32.dp)
            noiseFactor(0.02f)
            backgroundColor(Color(0x12000000))
            progressive(
                HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            // 真机 Beta10：**核心是误触，不是方向**。
            //   用户反馈：左右滑唤出控制台不会误触，但换任何手势都可能
            //   被当成"点了后面的歌词"→ 直接跳走一大截。
            //   病根：这里是 PointerEventPass.Initial（只读不消费），
            //   同一批事件**照样传到歌词行的 detectTapGestures**，于是一次滑动
            //   既唤出控制台、又触发了某行的 onTap → seek。
            //
            //   修法：不改方向（上下左右滑都能唤出，和左右滑共存），
            //   但**一旦判定是滑动，就盖一个"手势锁"**，歌词行的点击在锁内一律丢弃。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var startX = -1f
                    var startY = -1f
                    var lastX = -1f
                    var lastY = -1f
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val c = e.changes.firstOrNull() ?: continue
                        if (c.pressed) {
                            if (startY < 0f) {
                                startX = c.position.x
                                startY = c.position.y
                            }
                            val totalDx = c.position.x - startX
                            val totalDy = c.position.y - startY
                            // 位移超过阈值 = 这是滑动，不是点击
                            val isSwipe = abs(totalDx) > 18f || abs(totalDy) > 18f
                            if (isSwipe) {
                                // 盖锁：歌词行的 onTap 在锁内一律丢弃（防误触跳歌）
                                swipeGuardMs = SystemClock.uptimeMillis()
                                interactionTick++
                                controlsVisible = true
                            }
                            lastX = c.position.x
                            lastY = c.position.y
                        } else {
                            startX = -1f
                            startY = -1f
                            lastX = -1f
                            lastY = -1f
                        }
                    }
                }
            }
    ) {
        // ——— 采样层：背景 + 歌词（全屏延伸到物理顶部） ———
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            // 【Apple Music 风格】封面抽象流动背景
            // （大图沉浸模式封面盖满全屏 —— 流动动画停摆，省电）
            AlbumBlurBackground(
                artworkUrl = track?.artworkUrl,
                palette = palette,
                modifier = Modifier.fillMaxSize(),
                // 控制栏淡出（1.75s 无操作）= 屏幕没有别的动态 → 颜料流动也停，
                // 省电的同时界面"安静下来"（渐隐是 420ms 动画，观感自然衔接）
                animated = showLyrics && controlsVisible
            )

            // 主体：歌词全屏 / 无歌词大封面（两种模式切换）
            Box(Modifier.fillMaxSize()) {
                if (!showLyrics) {
                    ArtworkModeContent(
                        track = track,
                        onBackToLyrics = { showLyrics = true }
                    )
                } else if (timeline != null && timeline.size > 0) {
                    LyricsView(
                        timeline = timeline,
                        positionMs = positionState,
                        modifier = Modifier.fillMaxSize(),
                        onLineClick = { line ->
                // 防误触（真机 Beta10）：滑动换出控制台时手指会掠过歌词行，
                // 那次"点击"必须丢弃，否则直接跳到后面某句、跳过一大截。
                val sinceSwipe = SystemClock.uptimeMillis() - swipeGuardMs
                if (sinceSwipe > 400L) {
                    vm.seekTo(line.startMs)
                }
            },
                        onLineLongPress = { line, press, rect ->
                            if (focusRequest == null) {
                                focusRequest = LyricFocusRequest(line, press, rect)
                            }
                        },
                        scrollPaused = focusRequest != null,
                        focusProgress = focusProgress.value,
                        topInsetPx = topBarBottomPx,
                        // 引擎侧等待态：歌词加载中 → 等待球占住第一行（1.0.74 根治"不显示"）
                        waitForLyrics = lyricsLoading
                    )
                } else {
                    LyricsEmptyState(loading = lyricsLoading)
                }
            }
        }

        // ——— 顶部常驻区（不随歌词滚动）：毛玻璃盖在歌词之上 ———
        // 大图模式下整条不显示（真机：大图模式左上角的小图不该出现，
        // 歌名/艺人改放到大图下方，按 Apple Music 的排法）
        if (showLyrics) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val bounds = it.boundsInRoot()
                        topBarBottomPx = bounds.bottom
                        topGlassOrigin = bounds.topLeft
                    }
                    .hazeBlur(
                        input = HazeInput.Backdrop(hazeState),
                        style = topGlass
                    )
                    .padding(top = statusBarTopDp)   // 状态栏高度=进入时记录值：隐藏后标题栏钉在原位
            ) {
                PlayerTopBar(
                    track = track,
                    vm = vm,
                    hazeState = hazeState,
                    onFavBounds = { favBtnRect = it },
                    onMoreBounds = { moreBtnRect = it },
                    onArtworkClick = { showLyrics = !showLyrics },
                    onEnterFullscreen = { showFullscreen = true }
                )
            }
        }

        // ——— 底部浮层：渐变毛玻璃背景（与标题栏同工艺，方向相反：底实上虚）——
        //     真机调整：毛玻璃延伸到屏幕最底（不留缝）、左右收窄 20dp
        //     （与顶栏封面左缘对齐）、上边再抬高一点
        // 渐隐（真机）：歌词模式 1.75s 淡出；大图模式不要玻璃板、控件常驻
        val controlsAlpha by animateFloatAsState(
            targetValue = if (showLyrics && !controlsVisible) 0f else 1f,
            animationSpec = tween(420)
        )
        // 空闲计时：只要还有操作，就把淡出倒计时重新拨一遍（1.75s）；
        // 歌词正在翻动 / 队列面板开着 / 正在长按聚焦 → 不淡出
        LaunchedEffect(interactionTick, showQueue, focusRequest, showLyrics) {
            controlsVisible = true
            // 大图模式不跑渐隐倒计时（控件恒显，只把玻璃板拿掉）
            if (showLyrics && !showQueue && focusRequest == null) {
                delay(1750L)
                controlsVisible = false
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = controlsAlpha }
                .pointerInput(Unit) {
                    detectTapGestures { if (!controlsVisible) controlsVisible = true }
                }
                // 大图模式：整块液态玻璃底板都不出现，控件直接放在流动背景上
                .then(
                    if (showLyrics) Modifier.hazeBlur(
                        input = HazeInput.Backdrop(hazeState),
                        style = bottomGlass
                    ) else Modifier
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 大图模式：歌名/艺人（左）+ 收藏/更多（右），贴着进度条上方
                if (!showLyrics) {
                    ArtworkInfoRow(
                        track = track,
                        vm = vm,
                        hazeState = hazeState,
                        onEnterFullscreen = { showFullscreen = true }
                    )
                }
                ProgressCapsule(vm = vm, palette = palette, hazeState = hazeState)
                ControlBar(
                    vm = vm,
                    hazeState = hazeState,
                    showLyrics = showLyrics,
                    glass = showLyrics,
                    onToggleLyrics = { showLyrics = !showLyrics },
                    onOpenQueue = { showQueue = true }
                )
            }
        }

        // ——— 沉浸式浮动歌词（长按触发，第八轮重构）：
//     水波完整播完 → 浮起 → 全屏一句；内容实时跟随播放行，每字独立图层 ———
        LyricFocusOverlay(
            timeline = timeline,
            request = overlayRequest,
            progress = focusProgress.value,
            topBarBottomPx = topBarBottomPx,
            positionMs = positionState,
            active = focusRequest != null,
            onDismiss = { focusRequest = null },
            modifier = Modifier.fillMaxSize()
        )

        // ——— 播放队列面板（真功能） ———
        if (showQueue) {
            QueueOverlay(
                vm = vm,
                onDismiss = { showQueue = false },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ——— 横屏全屏播放（最上层覆盖） ———
        if (showFullscreen) {
            LandscapePlayerOverlay(
                vm = vm,
                onExit = { showFullscreen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ---------------- 顶栏（常驻：封面 62 + 标题 19sp + 玻璃图标钮） ----------------

@Composable
private fun PlayerTopBar(
    track: Track?,
    vm: PlayerViewModel,
    hazeState: HazeState,
    onFavBounds: (Rect) -> Unit,
    onMoreBounds: (Rect) -> Unit,
    onArtworkClick: () -> Unit,
    onEnterFullscreen: () -> Unit
) {
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavorite = track?.id?.let { it in favorites } ?: false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track?.artworkUrl,
            contentDescription = "切换封面模式",
            modifier = Modifier
                .size(62.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onArtworkClick)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.title.orEmpty(),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track?.artist.orEmpty(),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 收藏 + 更多：液态玻璃圆钮（bounds 上报给顶部毛玻璃，用于精确"挖孔"）
        Box(Modifier.onGloballyPositioned { onFavBounds(it.boundsInRoot()) }) {
            GlassIconButton(
                icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.StarBorder,
                onClick = { track?.id?.let(vm::toggleFavorite) },
                tint = if (isFavorite) YunoAccent else Color.White.copy(alpha = 0.92f),
                size = 48.dp,
                iconSize = 22.dp,
                hazeState = hazeState
            )
        }
        Spacer(Modifier.width(8.dp))
        var moreMenu by remember { mutableStateOf(false) }
        Box(Modifier.onGloballyPositioned { onMoreBounds(it.boundsInRoot()) }) {
            GlassIconButton(
                icon = Icons.Rounded.MoreVert,
                onClick = { moreMenu = true },
                size = 48.dp,
                iconSize = 22.dp,
                hazeState = hazeState
            )
            DropdownMenu(
                expanded = moreMenu,
                onDismissRequest = { moreMenu = false },
                containerColor = Color(0xF216161E)
            ) {
                DropdownMenuItem(
                    text = { Text("全屏播放", color = Color.White) },
                    onClick = {
                        moreMenu = false
                        onEnterFullscreen()
                    }
                )
            }
        }
    }
}

// ---------------- 进度胶囊（独立液态玻璃 + 专辑色波浪） ----------------

/**
 * 独立的液态玻璃胶囊进度条（与迷你控制器同款工艺）：
 *  · 不显示时长，只有一条波浪线
 *  · 已播段 = 专辑色高饱和渐变；未播段 = 白 0.22
 *  · 播放中：波浪起伏 + 相位流动；暂停：拉直 + 当前位置竖线
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun ProgressCapsule(
    vm: PlayerViewModel,
    palette: ArtworkPalette,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    val position by vm.positionMsFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val duration by vm.durationMsFlow.collectAsStateWithLifecycle(initialValue = 0L)
    val isPlaying by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)

    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction
        ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val capsule = RoundedCornerShape(26.dp)
    val style = rememberLiquidGlassStyle(shape = capsule)

    // 暂停"弹簧拉直"：波幅带过冲回弹的动画（像把弯弯的进度条拽直又微颤）
    val waveScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "waveScale"
    )

    // 波浪相位（播放时流动）
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing)),
        label = "phase"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp)
            .hazeGlass(
                input = HazeInput.Backdrop(hazeState),
                style = style
            )
            .clip(capsule)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) {
                        onSeekFraction(offset.x / size.width, duration, vm)
                    }
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragFraction = (offset.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        dragFraction?.let { f ->
                            if (duration > 0) vm.seekTo((f * duration).toLong())
                        }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                    onHorizontalDrag = { change, _ ->
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .padding(horizontal = 20.dp)
        ) {
            val cy = size.height / 2f
            val playing = isPlaying && dragFraction == null
            val amplitude = 2.4.dp.toPx() * waveScale
            val wavelength = 34.dp.toPx()
            val strokeWidth = 4.dp.toPx()
            val twoPi = (2.0 * PI).toFloat()

            fun yAt(x: Float): Float =
                cy + amplitude * sin(x / wavelength * twoPi + phase)

            fun wavePath(fromX: Float, toX: Float): Path {
                val p = Path()
                if (toX <= fromX) return p
                p.moveTo(fromX, yAt(fromX))
                var x = fromX
                while (x < toX) {
                    x = (x + 4f).coerceAtMost(toX)
                    p.lineTo(x, yAt(x))
                }
                return p
            }

            val playedX = size.width * fraction

            // 未播轨迹（浅）
            drawPath(
                path = wavePath(0f, size.width),
                color = Color.White.copy(alpha = 0.22f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            // 已播：专辑色高饱和渐变（更显眼）
            if (playedX > 1f) {
                drawPath(
                    path = wavePath(0f, playedX),
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            palette.vibrant.boostSaturation(1.4f),
                            palette.dominant.boostSaturation(1.25f),
                            palette.vibrant.boostSaturation(1.4f)
                        ),
                        startX = 0f,
                        endX = size.width
                    ),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            // 暂停：当前位置竖线
            if (!playing) {
                val half = 6.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(playedX, cy - half),
                    end = Offset(playedX, cy + half),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

private fun onSeekFraction(fraction: Float, duration: Long, vm: PlayerViewModel) {
    vm.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
}

// ---------------- 控制栏（液态玻璃 + 素控键） ----------------

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun ControlBar(
    vm: PlayerViewModel,
    hazeState: HazeState,
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    glass: Boolean = true
) {
    // 控制栏单独更透（真机反馈："控制暂停的那一块不是特别通透，再透一点"）
    // 真机反馈：1.5 倍太大 —— 收回，以原始尺寸为基准 ×1.1；R 角保持圆润
    val barStyle = rememberLiquidGlassStyle(
        shape = RoundedCornerShape(32.dp),
        surfaceAlpha = 0.10f
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 大图模式：控制板玻璃也去掉（只留控件），歌词模式保留液态玻璃
            .then(
                if (glass) Modifier.hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = barStyle
                ) else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        ControlButtons(vm = vm)
        Spacer(Modifier.height(16.dp))
        BottomIconRow(
            showLyrics = showLyrics,
            onToggleLyrics = onToggleLyrics,
            onOpenQueue = onOpenQueue
        )
    }
}

/**
 * 控制键排 —— 素控键（无圆底），对照照片：
 * shuffle | ◀◀ | ▶/⏸ | ▶▶ | repeat
 * 双三角同向同大小（42dp）；中间启停略大（48dp ≈ 大 18px）。
 */
@Composable
private fun ControlButtons(vm: PlayerViewModel) {
    val isPlaying by vm.isPlayingFlow.collectAsStateWithLifecycle(initialValue = false)
    val shuffle by vm.shuffleFlow.collectAsStateWithLifecycle(initialValue = false)
    val repeat by vm.repeatFlow.collectAsStateWithLifecycle(initialValue = RepeatMode.OFF)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToggleIcon(active = shuffle, onClick = vm::toggleShuffle) {
            Icon(
                Icons.Rounded.Shuffle,
                contentDescription = "随机播放",
                tint = if (shuffle) YunoAccent else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 上一首：双三角向左（与下一首同大小同风格）
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .clickable { vm.previous() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.FastRewind,
                contentDescription = "上一首",
                tint = Color.White,
                modifier = Modifier.size(46.dp)
            )
        }

        // 启停：原始 64dp 为基准 ×1.1 ≈ 70dp
        Box(
            Modifier
                .size(92.dp)
                .clip(CircleShape)
                .clickable { vm.togglePlayPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(70.dp)
            )
        }

        // 下一首：双三角向右
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .clickable { vm.next() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.FastForward,
                contentDescription = "下一首",
                tint = Color.White,
                modifier = Modifier.size(46.dp)
            )
        }

        ToggleIcon(active = repeat != RepeatMode.OFF, onClick = vm::cycleRepeat) {
            Icon(
                imageVector = if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "循环模式",
                tint = if (repeat != RepeatMode.OFF) YunoAccent else Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun ToggleIcon(
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 底部图标行：歌词（切换歌词/大封面）+ 播放队列（真功能）—— 统一样式、绝对居中 */
@Composable
private fun BottomIconRow(
    showLyrics: Boolean,
    onToggleLyrics: () -> Unit,
    onOpenQueue: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleLyrics),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Lyrics,
                contentDescription = "歌词 / 封面模式",
                tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.38f),
                modifier = Modifier.size(25.dp)
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenQueue),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.FormatListBulleted,
                contentDescription = "播放队列",
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

// ---------------- 播放队列面板（真机要求：做成真功能） ----------------

@Composable
private fun QueueOverlay(
    vm: PlayerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue by vm.queueFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val currentIndex by vm.queueIndexFlow.collectAsStateWithLifecycle(initialValue = 0)

    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.78f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xF214141C))
                // 吞掉面板内点击，避免误关闭
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "播放队列",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${currentIndex + 1} / ${queue.size}",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp
                )
            }
            // 拖动排序（长按拖动）+ 移出队列（行尾 ×）
            // 真机需求：队列要能拖动排序、能把歌移出去
            var draggingIndex by remember { mutableIntStateOf(-1) }
            var dragOffsetY by remember { mutableFloatStateOf(0f) }
            var rowHeightPx by remember { mutableFloatStateOf(0f) }

            LazyColumn(Modifier.fillMaxSize()) {
                // key 只跟曲目走（不能掺 index，否则拖动重排时会错位重建）
                itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
                    val isDragging = index == draggingIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    translationY = dragOffsetY
                                    val s = 1.02f
                                    scaleX = s
                                    scaleY = s
                                }
                            }
                            .onGloballyPositioned {
                                if (rowHeightPx <= 0f) rowHeightPx = it.size.height.toFloat()
                            }
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffsetY += amount.y
                                        if (rowHeightPx > 0f) {
                                            val shift = (dragOffsetY / rowHeightPx).toInt()
                                            // ⚠️ 攻击性检查（Beta14）：queue 为空时
                                            //    coerceIn(0, -1) 会抛 IllegalArgumentException
                                            val target = if (queue.isEmpty()) 0 else
                                                (index + shift).coerceIn(0, queue.lastIndex)
                                            if (target != index) {
                                                vm.moveInQueue(index, target)
                                                draggingIndex = target
                                                dragOffsetY -= shift * rowHeightPx
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                    }
                                )
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackRow(
                            track = track,
                            onClick = { vm.playAt(index) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            leadingArtwork = true,
                            playing = index == currentIndex,
                            trailingText = formatDuration(track.durationMs)
                        )
                        IconButton(
                            onClick = { vm.removeFromQueue(index) },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "移出队列",
                                tint = Color.White.copy(alpha = 0.42f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

// ---------------- 大封面模式（真机要求：无歌词的全屏封面） ----------------

// 大图沉浸模式（真机：照 Apple Music 的排法重做）
//   · 顶栏整条不显示 —— 左上角小图不该出现
//   · 大图不再居中偏下：整体上移、略收小（0.84 屏宽）
//   · 歌名/艺人放大图下方左侧，收藏/更多玻璃钮在右侧
//   · 图中没有的功能不抄（Dolby / AirPlay 之类一律不做）
//   · 底部保持我们的 M3 波浪进度条 + 控制板
@Composable
private fun ArtworkModeContent(
    track: Track?,
    onBackToLyrics: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 真机：图片再大一点、整体往上
        Spacer(Modifier.weight(0.40f))
        AsyncImage(
            model = track?.artworkUrl,
            contentDescription = "切回歌词",
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(1f)
                .shadow(
                    elevation = 26.dp,
                    shape = RoundedCornerShape(16.dp),
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.45f),
                    spotColor = Color.Black.copy(alpha = 0.55f)
                )
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onBackToLyrics)
        )
        // 下方留白给「信息行 + 波浪进度条 + 控件」，让大图明显偏上
        Spacer(Modifier.weight(1.65f))
    }
}

/**
 * 大图模式的信息行：贴在进度条上方 ——
 * 左侧歌名/艺人，右侧收藏 + 更多（与歌词模式顶栏同一套玻璃圆钮）。
 */
@Composable
private fun ArtworkInfoRow(
    track: Track?,
    vm: PlayerViewModel,
    hazeState: HazeState,
    onEnterFullscreen: () -> Unit
) {
    val favorites by vm.favoriteIds.collectAsStateWithLifecycle(initialValue = emptySet())
    val isFavorite = track?.id?.let { it in favorites } ?: false
    var moreMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.title.orEmpty(),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = track?.artist.orEmpty(),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        GlassIconButton(
            icon = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.StarBorder,
            onClick = { track?.id?.let(vm::toggleFavorite) },
            tint = if (isFavorite) YunoAccent else Color.White.copy(alpha = 0.92f),
            size = 48.dp,
            iconSize = 22.dp,
            hazeState = hazeState
        )
        Spacer(Modifier.width(8.dp))
        Box {
            GlassIconButton(
                icon = Icons.Rounded.MoreVert,
                onClick = { moreMenu = true },
                size = 48.dp,
                iconSize = 22.dp,
                hazeState = hazeState
            )
            DropdownMenu(
                expanded = moreMenu,
                onDismissRequest = { moreMenu = false },
                containerColor = Color(0xF216161E)
            ) {
                DropdownMenuItem(
                    text = { Text("全屏播放", color = Color.White) },
                    onClick = {
                        moreMenu = false
                        onEnterFullscreen()
                    }
                )
            }
        }
    }
}

// ---------------- 歌词空态 ----------------

@Composable
private fun LyricsEmptyState(loading: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (loading) {
            Text(
                text = "正在获取歌词…",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 15.sp
            )
        }
    }
}

// ---------------- 顶部毛玻璃"挖孔"遮罩（AGSL，Android 13+） ----------------

/**
 * 双圆孔遮罩：两个按钮的圆形区域返回 0（挖掉毛玻璃效果），
 * 其余区域返回 1（毛玻璃照常）。坐标是相对被绘制节点的像素坐标。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ButtonHolesMask(
    private val holeA: Offset,
    private val holeB: Offset,
    private val radius: Float
) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val shader = RuntimeShader(
            """
            uniform float2 uA;
            uniform float2 uB;
            uniform float uR;
            half4 main(float2 p) {
                float d = min(distance(p, uA), distance(p, uB)) - uR;
                float a = smoothstep(-1.0, 1.0, d);
                return half4(a, a, a, a);
            }
            """
        )
        shader.setFloatUniform("uA", holeA.x, holeA.y)
        shader.setFloatUniform("uB", holeB.x, holeB.y)
        shader.setFloatUniform("uR", radius)
        return shader
    }
}
