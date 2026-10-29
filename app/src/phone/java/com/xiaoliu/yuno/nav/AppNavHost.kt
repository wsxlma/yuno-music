package com.xiaoliu.yuno.nav

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import com.xiaoliu.yuno.core.ui.component.BottomTab
import com.xiaoliu.yuno.core.ui.component.LiquidGlassBottomBar
import com.xiaoliu.yuno.core.ui.theme.YunoInk
import com.xiaoliu.yuno.di.AppGraph
import com.xiaoliu.yuno.feature.detail.AlbumDetailScreen
import com.xiaoliu.yuno.feature.detail.AlbumDetailViewModel
import com.xiaoliu.yuno.feature.detail.PlaylistDetailScreen
import com.xiaoliu.yuno.feature.detail.PlaylistDetailViewModel
import com.xiaoliu.yuno.feature.home.HomeScreen
import com.xiaoliu.yuno.feature.home.HomeViewModel
import com.xiaoliu.yuno.feature.library.AlbumWallScreen
import com.xiaoliu.yuno.feature.library.AlbumWallViewModel
import com.xiaoliu.yuno.feature.player.FullPlayerScreen
import com.xiaoliu.yuno.feature.player.MiniPlayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xiaoliu.yuno.feature.player.PlayerViewModel
import com.xiaoliu.yuno.feature.search.SearchScreen
import com.xiaoliu.yuno.feature.search.SearchViewModel
import com.xiaoliu.yuno.ui.shareYunoLog
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 根导航：
 *   main（三 Tab 主壳 + 迷你播放器 + Liquid Glass 底栏）
 *   ├── player（全屏播放页：Apple 风格歌词 + M3 毛玻璃控制栏）
 *   ├── album/{albumId}（专辑详情）
 *   └── playlist/{playlistId}（歌单详情）
 * 二级界面（album/playlist）共享 root 层的迷你播放器（真机反馈：
 * 进二级页后底部也要有控制器胶囊），主壳自己的迷你条在此时隐藏。
 */
@Composable
fun AppNavHost(graph: AppGraph, autoOpenPlayer: Boolean = false) {
    val rootNav = rememberNavController()

    // 播放器 VM 提升到根级：歌词只加载一次，迷你播放器与全屏页共享同一实例
    val playerVm: PlayerViewModel = viewModel(factory = graph.viewModelFactory)

    val entry by rootNav.currentBackStackEntryAsState()
    val currentRootRoute = entry?.destination?.route
    val onDetailScreen =
        currentRootRoute == "album/{albumId}" || currentRootRoute == "playlist/{playlistId}"

    // 二级页面的采样层（详情页与 root 迷你播放器共用同一 hazeState）
    val rootHazeState = rememberHazeState()

    val hasTrack by remember {
        PlaybackStateHub.state.map { it.hasTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    // ——— 播放页 "跟手" sheet（回滚到 1.0.59 稳定模型） ———
    // 1.0.60~1.0.62 三轮"按手绘图重做"（面板上移/迷你条上滑/Tab 下推联动）
    // 真机全部失败（面板卡上半屏、底栏消失），按用户指令回滚：
    // 播放页整页位移 = 收起进度 × (SHEET_TRAVEL + 84dp)，从屏幕底部长开，
    // 底栏随进度下沉 + 淡出（sheetCover）。跟手/甩开手感（1.0.24 钦定）保留。
    val sheetProgress = remember { Animatable(1f) }
    var sheetVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val sheetTravelPx = with(density) { 260.dp.toPx() }

    fun openPlayer() {
        if (!sheetVisible) sheetVisible = true
        scope.launch {
            sheetProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = 0.92f,
                    stiffness = Spring.StiffnessMedium
                )
            )
        }
    }

    fun closePlayer() {
        scope.launch {
            sheetProgress.animateTo(1f, tween(300, easing = FastOutLinearInEasing))
            sheetVisible = false
        }
    }

    // 通知点击跳转（"open_lyrics" extra）：App 启动后若有正在播放的歌 → 自动打开歌词页
    LaunchedEffect(Unit) {
        if (autoOpenPlayer && hasTrack) {
            kotlinx.coroutines.delay(200L)   // 等首帧渲染完再弹歌词页
            openPlayer()
        }
    }

    // 迷你条累计位移（向上为负）→ 只走 SHEET_TRAVEL 这段，拖一点就够
    val onMiniDrag: (Float) -> Unit = { totalPx ->
        val p = (1f + totalPx / sheetTravelPx).coerceIn(0f, 1f)
        if (p < 1f && !sheetVisible) sheetVisible = true
        scope.launch { sheetProgress.snapTo(p) }
    }
    // 松手：速度优先 —— 向上甩（< -350px/s）无论拖多少都展开；
    // 否则过半展开、不过半回弹
    val onMiniDragEnd: (Float) -> Unit = { velocity ->
        val upwardFling = velocity < -350f
        val halfway = sheetProgress.value < 0.5f
        if (upwardFling || halfway) {
            openPlayer()
        } else {
            scope.launch {
                sheetProgress.animateTo(1f, tween(220, easing = FastOutLinearInEasing))
                sheetVisible = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = rootNav, startDestination = "main") {
            composable(route = "main") {
                MainShell(
                    graph = graph,
                    playerVm = playerVm,
                    onAlbumClick = { album -> rootNav.navigate("album/${album.id}") },
                    onPlaylistClick = { playlist -> rootNav.navigate("playlist/${playlist.id}") },
                    onOpenPlayer = { openPlayer() },
                    miniPlayerHidden = onDetailScreen,
                    onMiniDrag = onMiniDrag,
                    onMiniDragEnd = onMiniDragEnd,
                    // 只在播放页存在期间推底栏；关掉后归零，不能把底栏永久顶下去
                    sheetCover = if (sheetVisible) 1f - sheetProgress.value else 0f
                )
            }

            composable(
                route = "album/{albumId}",
                arguments = listOf(navArgument("albumId") { type = NavType.StringType })
            ) {
                DetailHost(onSelfClose = { rootNav.popBackStack() }) { close ->
                    val vm: AlbumDetailViewModel = viewModel(factory = graph.viewModelFactory)
                    AlbumDetailScreen(
                        vm = vm,
                        onBack = close,
                        hazeState = rootHazeState
                    )
                }
            }

            // 歌单详情（资料库歌单点开）
            composable(
                route = "playlist/{playlistId}",
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
            ) {
                DetailHost(onSelfClose = { rootNav.popBackStack() }) { close ->
                    val vm: PlaylistDetailViewModel = viewModel(factory = graph.viewModelFactory)
                    PlaylistDetailScreen(
                        vm = vm,
                        onBack = close,
                        hazeState = rootHazeState
                    )
                }
            }
        }

        // 二级界面的迷你控制器（真机反馈：这里也要有播放胶囊；离底留一点气口）
        if (onDetailScreen && hasTrack) {
            MiniPlayer(
                vm = playerVm,
                onExpand = { openPlayer() },
                hazeState = rootHazeState,
                onDragUpdate = onMiniDrag,
                onDragEnd = onMiniDragEnd,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 10.dp)
            )
        }

        // ——— 播放页 sheet（1.0.59 稳定模型：整页位移 + 缩放 + 渐隐） ———
        // 真机反馈修正：播放页要从"迷你控制器所在的位置"长出来，不是从屏幕最底下。
        // 所以收起态只往下挪到迷你条那一带（SHEET_TRAVEL），剩下的靠缩放/渐隐收干净。
        if (sheetVisible) {
            val p = sheetProgress.value
            // 位移：收起时整块落在底部控制区附近
            val travel = with(density) {
                val bottomBarArea = 84.dp.toPx() // 胶囊 Tab 一带
                sheetTravelPx + bottomBarArea
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = p * travel
                        // 从 0.86 放大到 1.0：像一张卡片从迷你条里长开
                        val s = 1f - 0.14f * p
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = (1f - p * 0.55f).coerceIn(0f, 1f)
                    }
            ) {
                FullPlayerScreen(
                    vm = playerVm,
                    onClose = { closePlayer() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * 主壳：三 Tab 内导航 + 迷你播放器 + Liquid Glass 底栏。
 *
 * 玻璃装配（Haze 2.x 关键步骤）：
 *   内容区 Modifier.hazeSource(hazeState)          ← 声明"可被采样的背板"
 *   底栏 / 迷你播放器 hazeGlass(input = Backdrop)  ← 在后面做折射+模糊+着色
 *   内容不消费 Scaffold 的 bottom padding —— 列表从玻璃底下穿过，这是液玻的灵魂。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun MainShell(
    graph: AppGraph,
    playerVm: PlayerViewModel,
    onAlbumClick: (Album) -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onOpenPlayer: () -> Unit,
    miniPlayerHidden: Boolean = false,
    onMiniDrag: (Float) -> Unit = {},
    onMiniDragEnd: (Float) -> Unit = {},
    sheetCover: Float = 0f
) {
    val hazeState = rememberHazeState()
    val innerNav = rememberNavController()
    val entry by innerNav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route


    // 有曲目才浮出迷你播放器（低频流，别整页订阅 50ms 的进度流）
    val hasTrack by remember {
        PlaybackStateHub.state.map { it.hasTrack }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    Scaffold(
        containerColor = YunoInk,
        bottomBar = {
            // 播放页展开时，底部控制区（迷你条 + 胶囊 Tab）被"推下去"：
            // 随 sheet 进度整体下沉 + 淡出，而不是被播放页生硬盖住
            val push = sheetCover
            Column(
                Modifier.graphicsLayer {
                    translationY = push * 96.dp.toPx()
                    alpha = (1f - push * 1.6f).coerceIn(0f, 1f)
                }
            ) {
                // 二级界面时迷你条交给 root 层显示（此处隐藏避免双份）
                if (hasTrack && !miniPlayerHidden) {
                    MiniPlayer(
                        vm = playerVm,
                        onExpand = onOpenPlayer,
                        hazeState = hazeState,
                        onDragUpdate = onMiniDrag,
                        onDragEnd = onMiniDragEnd
                    )
                }
                LiquidGlassBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { tab ->
                        innerNav.navigate(tab.route) {
                            popUpTo(innerNav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    hazeState = hazeState
                )
            }
        }
    ) { innerPadding ->
        // 底栏（迷你控制器 + 胶囊 Tab）实际高度：内容区要让出来。
        // 之前这里用 `{ _ -> }` 把 innerPadding 扔掉，内容一路铺到屏幕底，
        // 专辑墙/三排墙才会被 tab 栏压住、胶囊按钮掉到最下面。
        val bottomBarHeight = innerPadding.calculateBottomPadding()
        Box(
            Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
        ) {
            // 背景：纯黑（真机反馈：要"像素全关"的真黑，不要渐变灰）
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
            NavHost(
                navController = innerNav,
                startDestination = BottomTab.HOME.route,
                // 三 Tab 页面统一避让状态栏（haze 采样层仍全屏，玻璃效果不受影响）
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                composable(BottomTab.HOME.route) {
                    val vm: HomeViewModel = viewModel(factory = graph.viewModelFactory)
                    HomeScreen(vm = vm, onAlbumClick = onAlbumClick)
                }
                // 2026-10-29 2.8.0b1：资料库页整页换成专辑墙（旧 LibraryScreen 已删除）
                composable(BottomTab.LIBRARY.route) {
                    val vm: AlbumWallViewModel = viewModel(factory = graph.viewModelFactory)
                    AlbumWallScreen(
                        vm = vm,
                        hazeState = hazeState,
                        bottomInset = bottomBarHeight,
                        onAlbumDetail = onAlbumClick,
                        onPlaylistDetail = onPlaylistClick
                    )
                }
                composable(BottomTab.SEARCH.route) {
                    val vm: SearchViewModel = viewModel(factory = graph.viewModelFactory)
                    val context = LocalContext.current
                    SearchScreen(
                        vm = vm,
                        onAlbumClick = onAlbumClick,
                        hazeState = hazeState,
                        onExportLogs = { shareYunoLog(context) },
                        onLogout = { graph.logoutNow() }
                    )
                }
            }
        }
    }
}

// ============================================================================
// 二级界面的自研转入/转出动画 —— 真机反馈：系统返回动画是"缩小到中间一闪消失"，
// 观感生硬。这里把动画拿回来自己做：
//   进入：轻微向右滑 + 渐显（弹簧感）
//   退出：先缩小（1 → 0.96）+ 渐隐 260ms，播完才真正退栈 —— 系统返回键与
//   页面返回按钮都走这条 close() 路径，不再出现"闪现"。
// ============================================================================

@Composable
private fun DetailHost(
    onSelfClose: () -> Unit,
    content: @Composable (close: () -> Unit) -> Unit
) {
    val enter = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    var exiting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(300, easing = FastOutLinearInEasing)
        )
    }

    fun close() {
        if (exiting) return
        exiting = true
        scope.launch {
            exit.animateTo(0f, tween(260, easing = FastOutLinearInEasing))
            onSelfClose()
        }
    }

    BackHandler(onBack = { close() })

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val e = enter.value
                val x = exit.value
                alpha = e * x
                translationX = (1f - e) * 64.dp.toPx()
                val s = 0.96f + 0.04f * e * x
                scaleX = s
                scaleY = s
            }
    ) {
        content({ close() })
    }
}
