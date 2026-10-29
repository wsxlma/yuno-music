package com.xiaoliu.yuno.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.ui.component.GlassIconButton
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 主专辑墙 —— 整页替换原「资料库」标签页。
 *
 * ── 版面 ────────────────────────────────────────────────────────────────
 * · 卡 = 封面铺满整卡，文字压在底部（三段渐变），没有独立文字条
 * · 卡间 4dp 缝（**从卡片尺寸里扣**，不是卡片内缩 —— 这样大小卡的缝才等宽）
 * · 部分卡横跨两列
 * · **主墙铺满整屏**（可以铺到迷你控制器/胶囊 Tab 后面 —— 用户钦定：
 *   只有歌单三排墙要让位，主墙不用）
 *
 * ── 布局：4 张手画模板 × 4 种镜像 = 16 种朝向（照 Folia 的 blockTemplates 思路）──
 * 跨列卡要求同一个 y 上两列同时空出来，逐列独立堆叠算不出来。所以每块是一张
 * **手画的卡位表**，三列收尾齐平（7.2U），块横纵重复：
 *   · 模板号按 (bx + by*2) 递增 → **相邻块永不撞同一个模板**
 *   · 镜像号来自坐标哈希 → 场子不会被读成一格一格的网格
 *   · 块号可以为负 → 四向无限，位置稳定
 *   · 卡高从 0.60U 到 2.10U 共 10 档 → 有中等尺寸过渡，不再只有大小两极
 * 改模板表必须保证每张三列齐平（BlockTemplate.height 里有 require 兜着）。
 *
 * ── 拖动性能（2026-10-29 2.8.0b5）──────────────────────────────────────
 * 上一版每帧把 `pan` 传进每张卡做 offset，等于拖动时整屏卡都在 relayout，
 * 低端机上直接掉帧。现在：
 *   · `pan` 只在 `graphicsLayer {}` 里读 → 拖动只是改一个图层变换，
 *     不触发任何重组、也不 relayout 每张卡
 *   · 卡片集合只在**跨桶**（每 3/4 列宽）时重算一次（derivedStateOf）
 *   · 液态玻璃球（选中卡的播放球）放在图层**之外**，否则会被图层隔离，
 *     采样不到背后的封面 → 玻璃消失
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun AlbumWallScreen(
    vm: AlbumWallViewModel,
    hazeState: HazeState,
    /** 底栏高度：只有第二屏（歌单三排墙）要让位，主墙不让 */
    bottomInset: Dp = 0.dp,
    onAlbumDetail: (Album) -> Unit,
    onPlaylistDetail: (Playlist) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by vm.state.collectAsStateWithLifecycle()

    // 系统返回手势：三排墙开着时先收掉它。
    // 之前没有这一层，手势返回会直接把资料库路由 pop 掉 → 掉回主页（用户报过）。
    BackHandler(enabled = state.openPlaylist != null) { vm.closePlaylist() }
    val items = state.items
    val density = LocalDensity.current

    // 顶部副标题（"28 张专辑 · 6 个歌单"）
    val albumSummary = remember(items) {
        val a = items.count { it is WallItem.AlbumTile }
        val p = items.count { it is WallItem.PlaylistTile }
        listOfNotNull(
            a.takeIf { it > 0 }?.let { "$it 张专辑" },
            p.takeIf { it > 0 }?.let { "$it 个歌单" }
        ).joinToString(" · ")
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        val viewportH = constraints.maxHeight.toFloat()
        // 列宽：一屏三列，再整体放大 1.25 倍 → 实际是一屏 2.4 列，
        // 磁贴明显变大，边缘露出半个列宽，反而更有"墙"的感觉（用户要求 2026-10-29）
        val unitPx = viewportW / 2.4f

        var pan by rememberSaveable(stateSaver = OffsetSaver) { mutableStateOf(Offset.Zero) }
        var centered by rememberSaveable { mutableStateOf(false) }

        // 入场波次（照 Folia 的 entrance wave）：进页面后 900ms 内，卡片按离左上角的
        // 距离依次淡入 + 从下方浮起；之后新滚出来的卡片直接显示，不再播。
        var entranceDone by remember { mutableStateOf(false) }
        var entranceStart by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            entranceStart = System.currentTimeMillis()
            delay(900)
            entranceDone = true
        }

        // 拖动惯性（2026-10-29 2.8.0b10）：松手后按速度继续滑，用系统同款 spline 衰减曲线
        val scope = rememberCoroutineScope()
        val flingDecay = remember(density) { splineBasedDecay<Float>(density) }
        var flingJob by remember { mutableStateOf<Job?>(null) }

        // 分桶：桶内卡片集合不变。derivedStateOf 保证 pan 每帧变化**不会**引起重组，
        // 只有跨到下一个桶（每 3/4 列宽）才通知一次。
        val bucketPx = (unitPx * 0.75f).coerceAtLeast(1f)
        val bucketX by remember(bucketPx) {
            derivedStateOf { (pan.x / bucketPx).roundToInt() }
        }
        val bucketY by remember(bucketPx) {
            derivedStateOf { (pan.y / bucketPx).roundToInt() }
        }

        // 卡与卡之间的缝（从尺寸里扣，不是卡片内缩 —— 否则大小卡的缝视觉不等宽）
        val gapPx = with(density) { 4.dp.toPx() }
        // 设备像素密度：平移取整要用
        val deviceDensity = density.density

        val cards = remember(bucketX, bucketY, items, viewportW, viewportH, unitPx, gapPx) {
            if (items.isEmpty()) emptyList()
            else buildVisibleCards(
                items = items,
                // 用桶原点做可见范围参考；卡片坐标永远是**世界坐标**（不含 pan）
                panX = bucketX * bucketPx,
                panY = bucketY * bucketPx,
                viewportW = viewportW,
                viewportH = viewportH,
                unitPx = unitPx,
                gapPx = gapPx
            )
        }

        // 首次进入：把第一张歌单卡对准屏幕正中（验收要求：播放列表在中心位，不用找）
        LaunchedEffect(items.size, viewportW, viewportH) {
            if (centered || items.isEmpty() || viewportW <= 0f) return@LaunchedEffect
            val idx = state.firstPlaylistIndex
            if (idx < 0) { centered = true; return@LaunchedEffect }
            val spot = spotOf(idx, items.size)
            val (cx, cy) = slotCenter(spot, unitPx)
            pan = Offset(viewportW / 2f - cx, viewportH / 2f - cy)
            centered = true
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = {
                            // 一按下去就把上一次的滑行停住
                            flingJob?.cancel()
                            tracker.resetTracking()
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            pan += drag
                        },
                        onDragEnd = {
                            val v = tracker.calculateVelocity()
                            if (v.x * v.x + v.y * v.y < 180f * 180f) return@detectDragGestures
                            val startX = pan.x
                            val startY = pan.y
                            flingJob = scope.launch {
                                // 两个轴各跑一条衰减曲线，互不干扰
                                launch {
                                    val ax = Animatable(0f)
                                    ax.animateDecay(v.x, flingDecay) {
                                        pan = Offset(startX + ax.value, pan.y)
                                    }
                                }
                                launch {
                                    val ay = Animatable(0f)
                                    ay.animateDecay(v.y, flingDecay) {
                                        pan = Offset(pan.x, startY + ay.value)
                                    }
                                }
                            }
                        }
                    )
                }
        ) {
            // 点「空白」取消选中：磁贴铺满后只有接缝/边缘算空白，所以放一层底下的点击层
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { vm.clearSelection() }
            )

            // ── 卡片层：整层平移（draw 阶段读 pan，不重组、不 relayout）──
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 平移按**设备像素**取整：Folia 在 wallView.ts 里为这件事写了长注释 ——
                        // 小数位每帧变化会让整层重新栅格化（低端机上就是拖动掉帧的元凶）。
                        // 取整后小数位固定，平移只是搬纹理。
                        translationX = (pan.x * deviceDensity).roundToInt() / deviceDensity
                        translationY = (pan.y * deviceDensity).roundToInt() / deviceDensity
                    }
            ) {
                cards.forEach { card ->
                    key(card.slot) {
                    WallCard(
                        item = card.item,
                        widthPx = card.widthPx,
                        heightPx = card.heightPx,
                        x = card.x,
                        y = card.y,
                        // 选中按**位置**判定：同一个歌单在墙上出现多次时，
                        // 只有被点的那一张亮（按 item.key 判定会一起亮 —— 用户报过）
                        selected = card.slot == state.selectedKey,
                        isBig = card.isBig,
                        // 用**世界坐标**算波次，不要读 pan —— 读 pan 会让拖动时整屏重组
                        entranceDelayMs = if (entranceDone) -1L else {
                            val steps = (card.x + card.y) / unitPx
                            (steps * 26f).roundToInt().coerceIn(0, 420).toLong()
                        },
                        onSelect = { vm.select(card.slot) },
                        onLongPress = {
                            when (val it = card.item) {
                                is WallItem.AlbumTile -> onAlbumDetail(it.album)
                                is WallItem.PlaylistTile -> onPlaylistDetail(it.playlist)
                            }
                        }
                    )
                    }
                }
            }

            // ── 顶部氛围层：从上往下压暗，磁贴从深处透上来（固定，不随墙移动）──
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.80f),
                            0.48f to Color.Black.copy(alpha = 0.34f),
                            1f to Color.Transparent
                        )
                    )
            )

            // ── 大标题：这面墙的"刊头" ──
            // 拖动时整块淡出（在 draw 阶段读 pan，不会引起重组）
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .graphicsLayer {
                        alpha = (1f - (abs(pan.x) + abs(pan.y)) / 640f).coerceIn(0f, 1f)
                    }
                    .padding(start = 22.dp, top = 10.dp)
            ) {
                Text(
                    text = "资料库",
                    color = Color.White,
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = albumSummary,
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 11.5.sp,
                    letterSpacing = 0.2.sp
                )
            }

            // ── 底部沉入渐变：磁贴往下融进底栏，不再被硬切 ──
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f)
                        )
                    )
            )

            // ── 选中卡的播放球：必须在卡片图层**之外** ──
            // 玻璃要采样背后的封面；被 graphicsLayer 隔到独立图层里就采样不到，
            // 表现就是"一个黑圆、看不出玻璃"。
            val selectedCard = cards.firstOrNull { it.slot == state.selectedKey }
            if (selectedCard != null) {
                val ballPx = with(density) { 40.dp.toPx() }
                val insetPx = with(density) { 6.dp.toPx() }
                Box(
                    Modifier.offset {
                        IntOffset(
                            (selectedCard.x + pan.x + selectedCard.widthPx - ballPx - insetPx).roundToInt(),
                            (selectedCard.y + pan.y + selectedCard.heightPx - ballPx - insetPx).roundToInt()
                        )
                    }
                ) {
                    GlassIconButton(
                        icon = Icons.Rounded.PlayArrow,
                        onClick = {
                            when (val it = selectedCard.item) {
                                is WallItem.AlbumTile -> vm.playAlbum(it.album)
                                is WallItem.PlaylistTile -> vm.openPlaylist(it.playlist)
                            }
                        },
                        size = 40.dp,
                        iconSize = 22.dp,
                        hazeState = hazeState
                    )
                }
            }

            if (state.loading && items.isEmpty()) {
                Text(
                    "正在铺墙…",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (items.isEmpty() && state.error != null) {
                Text(
                    state.error.orEmpty(),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 第二屏：歌单三排胶卷墙（盖在主墙之上；主墙的平移与选中都保留）
            // 这里**不能**套 AnimatedVisibility —— 它的 alpha 图层会让玻璃采样不到背景。
            run {
                val detail = state.openPlaylist
                if (detail != null) {
                    PlaylistMarqueeOverlay(
                        vm = vm,
                        detail = detail,
                        loading = state.playlistLoading,
                        selectedKey = state.selectedKey,
                        bottomInset = bottomInset,
                        onExit = { vm.closePlaylist() }
                    )
                }
            }
        }
    }
}

// ============================================================================
// 积木块模板 —— 4 张手画模板 × 4 种镜像 = 16 种朝向（2026-10-29 2.8.0b11）
//
// 思路来自 Folia 的 `src/components/wall/blockTemplates.ts`：
//   · 每块是一张**手画**的卡位表，必须精确铺满、三列收尾齐平（否则块与块之间开洞）
//   · 模板号按 (bx + by*2) 线性递增 → **相邻块永不撞同一个模板**
//   · 镜像号来自坐标哈希 → 场子不会被读成一格一格的网格
//   · 4 模板 × 4 镜像（水平翻/垂直翻/都翻/都不翻）= 16 种朝向，重复周期从 2 变成 16
//
// 四张模板的块高都是 7.2U，卡高从 0.60U 到 2.10U 共 10 个档位 ——
// 这是"上一版只有 1.05~1.95 一档大小"和"没有中等尺寸过渡"的补丁。
// ============================================================================

/** 一块里的一张卡：起始列、跨几列、高度（单位 = 列宽） */
private data class Tile(val col: Int, val span: Int, val h: Float)

/** 一张模板：卡位表 + 预算好的 y 偏移 + 块高 */
private class BlockTemplate(val tiles: List<Tile>) {
    val ys: FloatArray = run {
        val cols = FloatArray(3)
        val out = FloatArray(tiles.size)
        tiles.forEachIndexed { i, t ->
            val y = if (t.span == 2) max(cols[t.col], cols[t.col + 1]) else cols[t.col]
            out[i] = y
            if (t.span == 2) {
                cols[t.col] = y + t.h
                cols[t.col + 1] = y + t.h
            } else {
                cols[t.col] = y + t.h
            }
        }
        out
    }

    val height: Float = let {
        val cols = FloatArray(3)
        tiles.forEach { t ->
            val y = if (t.span == 2) max(cols[t.col], cols[t.col + 1]) else cols[t.col]
            if (t.span == 2) {
                cols[t.col] = y + t.h
                cols[t.col + 1] = y + t.h
            } else {
                cols[t.col] = y + t.h
            }
        }
        require(abs(cols[0] - cols[1]) < 1e-3f && abs(cols[1] - cols[2]) < 1e-3f) {
            "模板三列必须齐平，否则块与块之间会开洞：${cols.toList()}"
        }
        cols.max()
    }
}

/**
 * 四张手画卡位表（块高统一 7.6U）—— 2.9.0 版。
 *
 * 上一版（b12）为了"别一开屏两张巨卡"把最大卡压到 1.50U，结果整面墙全成了
 * 差不多大的方块，反而没有层次。用户反馈："周围出现适当的一点巨卡还是不错的"。
 *
 * 所以这一版：**四张模板里只有两张带巨卡**（模板 1 的 2.40U @col0、模板 3 的 2.40U @col1），
 * 另两张（模板 0 / 2）完全没有巨卡，当"安静的底子"。
 * 巨卡占 2 列、2.40U 高 —— 一屏 2.4 列宽、4.8U 高，所以一张巨卡约占屏幕 1/4，
 * 是"适当的一点"，不会顶满画面。
 */

private val TEMPLATE_0 = BlockTemplate(
    listOf(
        Tile(0, 1, 1.15f), Tile(1, 1, 1.30f), Tile(2, 1, 1.50f), Tile(0, 1, 0.70f),
        Tile(1, 1, 1.50f), Tile(2, 1, 1.00f), Tile(0, 1, 1.00f), Tile(2, 1, 1.30f),
        Tile(1, 1, 0.85f), Tile(0, 1, 1.30f), Tile(1, 1, 1.30f), Tile(2, 1, 1.50f),
        Tile(0, 1, 1.15f), Tile(1, 1, 1.15f), Tile(0, 1, 1.15f), Tile(2, 1, 1.30f),
        Tile(1, 1, 1.50f), Tile(0, 1, 1.15f), Tile(2, 1, 1.00f),
    )
)

// 模板 1: 16 张，块高 7.6，巨卡 2.40U @col0
//   切点 c0 [2.4, 3.9, 4.6, 5.9, 6.9, 7.6] | c1 [2.4, 3.7, 5.0, 6.3, 7.6] | c2 [1.5, 2.5, 3.8, 5.3, 6.6, 7.6]
private val TEMPLATE_1 = BlockTemplate(
    listOf(
        Tile(0, 2, 2.40f), Tile(2, 1, 1.50f), Tile(2, 1, 1.00f), Tile(0, 1, 1.50f),
        Tile(1, 1, 1.30f), Tile(2, 1, 1.30f), Tile(1, 1, 1.30f), Tile(2, 1, 1.50f),
        Tile(0, 1, 0.70f), Tile(0, 1, 1.30f), Tile(1, 1, 1.30f), Tile(2, 1, 1.30f),
        Tile(0, 1, 1.00f), Tile(1, 1, 1.30f), Tile(2, 1, 1.00f), Tile(0, 1, 0.70f),
    )
)

// 模板 2: 20 张，块高 7.6，巨卡 无
//   切点 c0 [1.3, 2.3, 3.6, 4.75, 6.05, 6.75, 7.6] | c1 [1.0, 2.3, 3.3, 4.8, 6.3, 7.6] | c2 [0.7, 2.0, 3.0, 4.3, 5.15, 6.45, 7.6]
private val TEMPLATE_2 = BlockTemplate(
    listOf(
        Tile(0, 1, 1.30f), Tile(1, 1, 1.00f), Tile(2, 1, 0.70f), Tile(2, 1, 1.30f),
        Tile(1, 1, 1.30f), Tile(0, 1, 1.00f), Tile(2, 1, 1.00f), Tile(0, 1, 1.30f),
        Tile(1, 1, 1.00f), Tile(2, 1, 1.30f), Tile(1, 1, 1.50f), Tile(0, 1, 1.15f),
        Tile(2, 1, 0.85f), Tile(0, 1, 1.30f), Tile(1, 1, 1.50f), Tile(2, 1, 1.30f),
        Tile(0, 1, 0.70f), Tile(1, 1, 1.30f), Tile(2, 1, 1.15f), Tile(0, 1, 0.85f),
    )
)

// 模板 3: 17 张，块高 7.6，巨卡 2.40U @col1
//   切点 c0 [1.3, 2.8, 4.1, 5.1, 6.6, 7.6] | c1 [2.4, 3.7, 4.4, 5.9, 6.6, 7.6] | c2 [2.4, 3.55, 4.25, 5.75, 6.75, 7.6]
private val TEMPLATE_3 = BlockTemplate(
    listOf(
        Tile(1, 2, 2.40f), Tile(0, 1, 1.30f), Tile(0, 1, 1.50f), Tile(1, 1, 1.30f),
        Tile(2, 1, 1.15f), Tile(0, 1, 1.30f), Tile(2, 1, 0.70f), Tile(1, 1, 0.70f),
        Tile(0, 1, 1.00f), Tile(2, 1, 1.50f), Tile(1, 1, 1.50f), Tile(0, 1, 1.50f),
        Tile(2, 1, 1.00f), Tile(1, 1, 0.70f), Tile(0, 1, 1.00f), Tile(1, 1, 1.00f),
        Tile(2, 1, 0.85f),
    )
)

private val TEMPLATES = listOf(TEMPLATE_0, TEMPLATE_1, TEMPLATE_2, TEMPLATE_3)

/** 块的统一高度（单位 = 列宽）。四张模板必须等高，否则纵向拼接会开洞。 */
private val BLOCK_H: Float = TEMPLATES[0].height

/** 整数哈希：给镜像用，保证同一块每次渲染拿到同一个朝向 */
private fun mixBlockCoords(bx: Int, by: Int): Int {
    var v = (bx * -0x61C88647) xor (by * -0x7A143589)
    v = (v xor (v ushr 15)) * 0x2c1b3c6d
    v = v xor (v ushr 12)
    return v and 0x7FFFFFFF
}

/** 块的朝向 = 模板号 × 4 + 镜像号 */
private fun blockOrientation(bx: Int, by: Int): Int {
    val n = TEMPLATES.size
    // 横向 +1、纵向 +2 → 相邻块永不撞同一个模板
    val templateIdx = floorMod(bx + by * 2, n)
    val mirror = (mixBlockCoords(bx, by) ushr 9) and 3
    return templateIdx * 4 + mirror
}

private fun blockHeightPx(unitPx: Float): Float = BLOCK_H * unitPx

/** 一条目在墙上的一个位置：块行、块列、块内序号、该块的朝向 */
private data class Spot(val by: Int, val bx: Int, val t: Int, val orientation: Int)

/**
 * 反查：哪个 (块列 bx, 块内序号 t) 上摆着 targetIndex 这个条目。
 *
 * 因为内容分配改成了「大卡优先」（rank 不再是 slot 序号），不能再直接用除法算，
 * 得按真实映射枚举一遍。只扫块行 0 的前几块 —— 首屏只需要一个确定的入口。
 */
private fun spotOf(targetIndex: Int, n: Int): Spot {
    if (n <= 0) return Spot(0, 0, 0, blockOrientation(0, 0))
    for (bx in 0..7) {
        val orientation = blockOrientation(bx, 0)
        val template = TEMPLATES[orientation shr 2]
        for (t in template.tiles.indices) {
            if (itemIndexAt(0, bx, orientation shr 2, t, n) == targetIndex) {
                return Spot(0, bx, t, orientation)
            }
        }
    }
    return Spot(0, 0, 0, blockOrientation(0, 0))
}

/** 某个位置在世界上（不含 pan）的中心点，单位 px */
private fun slotCenter(spot: Spot, unitPx: Float): Pair<Float, Float> {
    val template = TEMPLATES[spot.orientation shr 2]
    val mirror = spot.orientation and 3
    val tile = template.tiles[spot.t]
    val col = if (mirror and 1 != 0) 3 - tile.col - tile.span else tile.col
    val relY = if (mirror and 2 != 0) template.height - template.ys[spot.t] - tile.h else template.ys[spot.t]
    val x = spot.bx * 3 * unitPx + col * unitPx + tile.span * unitPx / 2f
    val y = spot.by * blockHeightPx(unitPx) + relY * unitPx + tile.h * unitPx / 2f
    return x to y
}

private fun floorMod(a: Int, b: Int): Int {
    val r = a % b
    return if (r < 0) r + b else r
}

/**
 * 每张模板内「卡的面积从大到小」的名次表。
 *
 * 照 Folia 的 `rankSlots.ts` 思路：让**大片先吃到排在前面的内容**，
 * 否则第一张专辑可能永远落在一个 0.70U 的小方格里，整面墙没有主角。
 */
private val TEMPLATE_RANK: Array<IntArray> = TEMPLATES.map { tpl ->
    tpl.tiles.indices
        .sortedByDescending { tpl.tiles[it].span * tpl.tiles[it].h }
        .toIntArray()
        .let { order ->
            // order[k] = 第 k 大的卡号；反过来存 rankOf[slot] = 它排第几
            IntArray(order.size).also { rank -> order.forEachIndexed { k, slot -> rank[slot] = k } }
        }
}.toTypedArray()

/**
 * (块行 by, 块列 bx, 模板内序号 t) → 条目下标。
 *
 * 步长取 16（一张模板最多 16 张卡）—— 四张模板张数不同，用固定步长才能保证
 * 不同块之间不会落到同一条目上而出现大片重复。
 */
private fun itemIndexAt(by: Int, bx: Int, templateIdx: Int, t: Int, n: Int): Int {
    val rank = TEMPLATE_RANK[templateIdx][t]
    return floorMod((by * 7 + bx) * 16 + rank, n)
}

private data class VisibleCard(
    val item: WallItem,
    /** 世界坐标（不含 pan） */
    val x: Float,
    val y: Float,
    val widthPx: Float,
    val heightPx: Float,
    /** 这张卡在墙上的**唯一位置**标识 —— 选中态按它判定，不按内容判定 */
    val slot: String,
    /** 跨两列的大片：字号更大、带副标题 */
    val isBig: Boolean
)

/**
 * 只装视口内的卡（四周各留一块的余量，覆盖分桶漂移）。
 * 块的排布是常量，所以这里只是把「可见的块 × 块内 16 张」枚举出来，非常便宜。
 */
private fun buildVisibleCards(
    items: List<WallItem>,
    panX: Float,
    panY: Float,
    viewportW: Float,
    viewportH: Float,
    unitPx: Float,
    gapPx: Float
): List<VisibleCard> {
    val n = items.size
    if (n == 0 || unitPx <= 0f) return emptyList()

    val blockW = 3 * unitPx
    val blockH = blockHeightPx(unitPx)

    // 余量只需要覆盖「分桶漂移」（最多 0.75 列宽），不用整整一块 ——
    // 一块是 3 列 × 7.2U 高，留整块会让在场卡片翻倍，拖动时白画。
    val marginX = unitPx * 0.8f
    val marginY = unitPx * 0.8f
    val left = -panX - marginX
    val right = -panX + viewportW + marginX
    val top = -panY - marginY
    val bottom = -panY + viewportH + marginY

    val bxFrom = floor(left / blockW).toInt()
    val bxTo = floor(right / blockW).toInt()
    val byFrom = floor(top / blockH).toInt()
    val byTo = floor(bottom / blockH).toInt()

    val out = ArrayList<VisibleCard>(200)
    for (by in byFrom..byTo) {
        for (bx in bxFrom..bxTo) {
            // 16 种朝向：模板号决定卡位表，镜像号决定翻不翻
            val orientation = blockOrientation(bx, by)
            val template = TEMPLATES[orientation shr 2]
            val mirror = orientation and 3
            val flipX = mirror and 1 != 0
            val flipY = mirror and 2 != 0

            template.tiles.forEachIndexed { t, tile ->
                val col = if (flipX) 3 - tile.col - tile.span else tile.col
                val relY = if (flipY) template.height - template.ys[t] - tile.h else template.ys[t]
                val x = bx * blockW + col * unitPx
                val y = by * blockH + relY * unitPx
                // 缝从卡片尺寸里扣（不是卡片内缩）：这样大小卡之间的缝才真正等宽
                val w = tile.span * unitPx - gapPx
                val h = tile.h * unitPx - gapPx
                if (x + w >= left && x <= right && y + h >= top && y <= bottom) {
                    out.add(
                        VisibleCard(
                            item = items[itemIndexAt(by, bx, orientation shr 2, t, n)],
                            x = x,
                            y = y,
                            widthPx = w,
                            heightPx = h,
                            slot = "$by:$bx:$t",
                            isBig = tile.span == 2 || tile.h >= 1.35f
                        )
                    )
                }
            }
        }
    }
    return out
}

/** Offset 的可保存器（重进页面/旋转后不丢墙的位置） */
private val OffsetSaver: Saver<Offset, List<Float>> = Saver(
    save = { listOf(it.x, it.y) },
    restore = { Offset(it[0], it[1]) }
)

// ============================================================================
// 单张卡
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WallCard(
    item: WallItem,
    widthPx: Float,
    heightPx: Float,
    x: Float,
    y: Float,
    selected: Boolean,
    /** 跨两列的大片：内边距更大 */
    isBig: Boolean,
    /** >=0 时播放入场动画（延迟毫秒数）；-1 = 直接显示 */
    entranceDelayMs: Long,
    onSelect: () -> Unit,
    onLongPress: () -> Unit
) {
    val density = LocalDensity.current
    val width: Dp = with(density) { widthPx.toDp() }
    val cardH: Dp = with(density) { heightPx.toDp() }

    // 入场：从下方 28dp 浮起 + 淡入（只播一次，之后新卡不播）
    val entrance = remember { Animatable(if (entranceDelayMs >= 0) 0f else 1f) }
    LaunchedEffect(entranceDelayMs) {
        if (entranceDelayMs >= 0 && entrance.value < 1f) {
            delay(entranceDelayMs)
            entrance.animateTo(
                1f,
                animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing)
            )
        }
    }

    // 选中：封面轻微"拎起来"（轻量放大）。
    // ★ 2.9.4 修正：scale 必须只作用在**封面内容**上，不能作用在整张卡上 ——
    //   上一版把 scale 加在卡片的 graphicsLayer 里，而 clip 在它内层（裁不住缩放），
    //   于是卡片整体放大 4.5%，跨两列的巨卡横向顶出格子约 9px，白色选中框跟着
    //   跑到隔壁卡上（用户报"周边白框超出边界"）。现在卡片本体尺寸不变，
    //   缩放交给内层封面、由父级 clip 裁在卡内 —— 白框永远严丝合缝贴着自己的边。
    val pop by animateFloatAsState(
        targetValue = if (selected) 1.045f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 620f),
        label = "pop"
    )

    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(width)
            .height(cardH)
            .graphicsLayer {
                alpha = entrance.value
                translationY = (1f - entrance.value) * 28.dp.toPx()
            }
            .clip(RoundedCornerShape(2.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
                onLongClick = onLongPress
            )
    ) {
        // 统一封面绘制：横图完整显示 + 上下模糊延伸（永不切字），竖图按重心裁
        CoverImage(
            url = item.artworkUrl,
            contentDescription = item.title,
            cardAspect = if (heightPx > 0f) widthPx / heightPx else 1f,
            modifier = Modifier
                .fillMaxSize()
                // 选中时封面自己轻微放大（父级 clip 收住，不溢出格子）
                .graphicsLayer {
                    scaleX = pop
                    scaleY = pop
                },
            blurRadius = 26.dp,
            scrim = 0.40f
        )

        // 底部渐变压暗，文字直接压在封面上
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.62f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.40f),
                        1f to Color.Black.copy(alpha = 0.85f)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = if (isBig) 10.dp else 6.dp,
                    end = if (isBig) 10.dp else 6.dp,
                    bottom = if (isBig) 9.dp else 5.dp
                )
        ) {
            // 字体回到统一规格（2026-10-29 2.8.0b10）：
            // 上一版给大片单独放大到 14sp + SemiBold + 负字距，反而更廉价 ——
            // 同一面墙上两种字号字重会显得"没定稿"。全部统一 10.5 / 9。
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 10.5.sp,
                lineHeight = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = item.subtitle,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f))
            )
        }
    }
}
