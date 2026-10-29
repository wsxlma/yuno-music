package com.xiaoliu.yuno.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.splineBasedDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
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
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.GlassChip
import com.xiaoliu.yuno.core.ui.component.GlassIconButton
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 歌单三排胶卷详情墙（2026-10-29 2.8.0b4-r）。
 *
 * ── 卡片形状：正方形为主，随机掺竖条 ──────────────────────────────────────
 * 上一版把卡宽统一压成「屏宽/3」→ 全部变成细竖条，封面被裁掉一大截，跟之前
 * 正方形能看全封面的观感差太远。现在：
 *   · 默认卡 = **正方形**（边长 = 排高），封面完整显示
 *   · 约 1/4 的卡是**竖条**（宽 = 排高 × 0.55），随机出现
 *   · 「随机」用曲目 id 的稳定哈希（hash % 4 == 0）—— 位置固定不跳动，
 *     重进同一歌单排布也一样，不会像真随机那样每次洗牌
 *
 * ── 布局 ────────────────────────────────────────────────────────────────
 * · 上方顶到头：没有顶栏，卡片从内容区最顶端开始；退出球浮在卡片上
 * · 下方一点不占：三排下沿 → 12dp → 胶囊 → 10dp → 迷你控制器/胶囊 Tab
 *   （底栏高度由外层 Scaffold 的 innerPadding 传进来，专辑墙已让位）
 *
 * 滚动仍用「卡片按下标虚拟定位 + graphicsLayer 连续位移」，不做 LazyRow 步进。
 * 因为卡宽不一，位置用**前缀和**算：x = baseX + 圈数×一圈宽 + 前缀[下标]。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun PlaylistMarqueeOverlay(
    vm: AlbumWallViewModel,
    detail: PlaylistDetail,
    loading: Boolean,
    selectedKey: String?,
    /** 底栏高度：三排墙的内容要停在它上面（主墙可以让位，这屏不行 —— 用户钦定） */
    bottomInset: Dp = 0.dp,
    onExit: () -> Unit
) {
    val tracks = detail.tracks
    val density = LocalDensity.current

    // ★ 页级采样源（2026-10-29 2.8.0b7）：液态玻璃必须能采样到**背后的内容**。
    //   之前这一屏自己铺了一整块不透明纯色，玻璃采到的就是纯色 → 什么都看不见，
    //   只剩一个箭头。现在把三排卡片标成采样源，玻璃浮层放在它的外面。
    val pageHaze = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        // 底色层：不参与采样（纯色进了采样源只会让玻璃采到一片纯色）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF070709))
        )

        // 内容层 = 采样源，画在玻璃元素的下面一层
        Column(
            Modifier
                .fillMaxSize()
                .padding(bottom = bottomInset)
                .hazeSource(pageHaze)
        ) {
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val totalH = constraints.maxHeight.toFloat()
                // 排间距 = 排内间距（用户反馈：三排之间的间距和排里两张图之间的间距不一样，
                // 看着就廉价）。卡内每边 1.5dp padding → 排内相邻两图之间是 3dp，
                // 所以排与排之间**不能**再加缝（再加就是 3+缝），这里取 0：
                // 排间 = 1.5 + 0 + 1.5 = 3dp，与排内完全一致。
                val rowH = (totalH / 3f).coerceAtLeast(1f)

                Column(Modifier.fillMaxSize()) {
                    MarqueeRow(
                        tracks = tracks.filterIndexed { i, _ -> i % 3 == 0 },
                        rowHeightPx = rowH,
                        reverse = false,            // 第 1 排：向左
                        durationMs = 96_000,
                        rowIndex = 0,
                        trackIndexOf = { rowIndex -> rowIndex * 3 },
                        selectedKey = selectedKey,
                        hazeState = pageHaze,
                        onSelectSlot = { slot -> vm.select(slot) },
                        onBall = { trackIndex -> vm.playPlaylistFrom(trackIndex) }
                    )
                    MarqueeRow(
                        tracks = tracks.filterIndexed { i, _ -> i % 3 == 1 },
                        rowHeightPx = rowH,
                        reverse = true,             // 第 2 排：向右
                        durationMs = 128_000,
                        rowIndex = 1,
                        trackIndexOf = { rowIndex -> rowIndex * 3 + 1 },
                        selectedKey = selectedKey,
                        hazeState = pageHaze,
                        onSelectSlot = { slot -> vm.select(slot) },
                        onBall = { trackIndex -> vm.playPlaylistFrom(trackIndex) }
                    )
                    MarqueeRow(
                        tracks = tracks.filterIndexed { i, _ -> i % 3 == 2 },
                        rowHeightPx = rowH,
                        reverse = false,            // 第 3 排：向左
                        durationMs = 112_000,
                        rowIndex = 2,
                        trackIndexOf = { rowIndex -> rowIndex * 3 + 2 },
                        selectedKey = selectedKey,
                        hazeState = pageHaze,
                        onSelectSlot = { slot -> vm.select(slot) },
                        onBall = { trackIndex -> vm.playPlaylistFrom(trackIndex) }
                    )
                }
            }

            // 胶囊：单独一条带，与上面的卡、下面的迷你条都留空隙
            // 给浮层胶囊留出占位：12dp 空档 + 胶囊 52dp + 10dp 空档
            Spacer(Modifier.height(74.dp))
        }

        // 两个胶囊必须是**玻璃浮层**，而且必须在采样源外面 ——
        // 塞在 hazeSource 的 Column 里会被 source 吞掉，玻璃不生效（用户报过"没套上玻璃"）。
        // 位置：左半屏正中 / 右半屏正中（两半各占一份，内部居中）。
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                // 水平内距与底部胶囊 Tab 栏一致（LiquidGlassBottomBar 也是 14dp）：
                // 「播放全部」左边贴着底下「主页」那一格的外壳左边，
                // 「随机播放」右边贴着底下搜索圆钮的右边。
                .padding(start = 14.dp, end = 14.dp, bottom = bottomInset + 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassChip(
                text = "播放全部",
                onClick = { vm.playPlaylistAll() },
                hazeState = pageHaze,
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 15.dp)
            )
            GlassChip(
                text = "随机播放",
                onClick = { vm.playPlaylistShuffle() },
                hazeState = pageHaze,
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                ),
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 15.dp)
            )
        }

        // 左上：液态玻璃退出球 —— 浮在内容之上，不占布局
        GlassIconButton(
            icon = Icons.AutoMirrored.Rounded.ArrowBack,
            onClick = onExit,
            size = 44.dp,
            iconSize = 20.dp,
            hazeState = pageHaze,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 10.dp)
        )

        if (tracks.isEmpty()) {
            Text(
                if (loading) "正在取歌单…" else "这个歌单没有曲目",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

/**
 * 竖条宽度档位（单位 = 排高）。
 *
 * 之前所有竖条都是同一个 0.55 —— 用户反馈"切得太规矩，看着还是廉价"。
 * 现在四档，按曲目 id 的稳定哈希取，同一首歌每次进都是同一档（不洗牌），
 * 但整排看过去宽度是不等的。
 */
private val NARROW_WIDTHS = floatArrayOf(0.62f, 0.70f, 0.78f)
private val WIDE_WIDTHS = floatArrayOf(1.18f, 1.30f, 1.44f, 1.58f)

/** 稳定哈希（去符号位，避免 abs(Int.MIN_VALUE) 仍是负数） */
private fun stableHash(track: Track): Int = track.id.hashCode() and 0x7FFFFFFF

/**
 * 卡片宽度（单位 = 排高）。
 *
 * 上一版最细给到 0.42 —— 用户反馈"有的改得特别特别细"，那一档已经去掉。
 * 现在分布：
 *   · 60% 正方形（1.00）—— 主体，封面完整
 *   · 20% 略窄（0.62 / 0.70 / 0.78）—— 最小 0.62，不会细成一条
 *   · 20% 变宽（1.18 / 1.30 / 1.44 / 1.58）—— 宽卡两侧用封面自身的模糊延伸填满
 */
private fun cardWidthRatio(track: Track): Float {
    val h = stableHash(track)
    val pick = (h / 11) % 10
    return when {
        pick < 6 -> 1.00f
        pick < 8 -> NARROW_WIDTHS[(h / 17) % NARROW_WIDTHS.size]
        else -> WIDE_WIDTHS[(h / 23) % WIDE_WIDTHS.size]
    }
}

/**
 * 一排胶卷。
 *
 * @param reverse true = 向右滚；false = 向左滚
 * @param durationMs 走完一整圈的时间（三排不同 → 速度有差异，但都不快）
 * @param trackIndexOf 把「本排行内下标」映射回歌单里的真实序号（用于从该首起播）
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
private fun MarqueeRow(
    tracks: List<Track>,
    rowHeightPx: Float,
    reverse: Boolean,
    durationMs: Int,
    /** 第几排（0/1/2）—— 参与选中位置标识，同一首歌不在多排之间联动 */
    rowIndex: Int,
    trackIndexOf: (rowIndex: Int) -> Int,
    selectedKey: String?,
    hazeState: HazeState,
    onSelectSlot: (slot: String) -> Unit,
    onBall: (trackIndex: Int) -> Unit
) {
    val density = LocalDensity.current
    if (tracks.isEmpty()) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(with(density) { rowHeightPx.toDp() })
        )
        return
    }

    val count = tracks.size
    // 卡宽：正方形 = 排高；竖条 = 排高 × 0.55。宽度不一，所以用前缀和定位。
    val widths = remember(tracks, rowHeightPx) {
        FloatArray(count) { i -> rowHeightPx * cardWidthRatio(tracks[i]) }
    }
    val prefix = remember(widths) {
        FloatArray(count + 1).also { p -> for (i in widths.indices) p[i + 1] = p[i] + widths[i] }
    }
    val cycleW = prefix[count]
    val avgW = cycleW / count

    var dragX by remember { mutableFloatStateOf(0f) }

    // 拖动惯性（2026-10-29 2.8.0b10）：松手后按速度继续滑一段再停
    val scope = rememberCoroutineScope()
    val flingDecay = remember(density) { splineBasedDecay<Float>(density) }

    val transition = rememberInfiniteTransition(label = "marquee")
    val f by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "marqueeF"
    )

    // 向左：从 0 递减到 -cycleW；向右：从 -cycleW 递增到 0。两端画面一致 → 无缝
    val autoX = if (reverse) (f * cycleW - cycleW) else (-f * cycleW)
    val baseX = autoX + dragX

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(with(density) { rowHeightPx.toDp() })
            .clipToBounds()
            .pointerInput(Unit) {
                val tracker = VelocityTracker()
                detectDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDrag = { change, drag ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragX += drag.x
                    },
                    onDragEnd = {
                        val vx = tracker.calculateVelocity().x
                        if (kotlin.math.abs(vx) < 120f) return@detectDragGestures
                        val start = dragX
                        scope.launch {
                            val anim = Animatable(0f)
                            anim.animateDecay(vx, flingDecay) {
                                dragX = start + anim.value
                            }
                        }
                    }
                )
            }
    ) {
        val viewportW = constraints.maxWidth.toFloat()
        // 从估算槽位起走：位置 = 圈数×一圈宽 + 前缀[下标]
        var slot = floor(-baseX / avgW).toInt() - 2
        var guard = 0
        while (guard++ < 64) {
            val cycles = Math.floorDiv(slot, count)
            val idx = slot - cycles * count
            val x = baseX + cycles * cycleW + prefix[idx]
            val w = widths[idx]
            if (x > viewportW) break
            if (x + w > 0f) {
                val track = tracks[idx]
                val slot = "$rowIndex:$idx"
                key(slot) {
                MarqueeCard(
                    track = track,
                    x = x,
                    cardWidthPx = w,
                    rowHeightPx = rowHeightPx,
                    selected = selectedKey == slot,
                    hazeState = hazeState,
                    onSelect = { onSelectSlot(slot) },
                    onBall = { onBall(trackIndexOf(idx)) }
                )
                }
            }
            slot++
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun MarqueeCard(
    track: Track,
    x: Float,
    cardWidthPx: Float,
    rowHeightPx: Float,
    selected: Boolean,
    hazeState: HazeState,
    onSelect: () -> Unit,
    onBall: () -> Unit
) {
    val density = LocalDensity.current
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), 0) }
            .width(with(density) { cardWidthPx.toDp() })
            .height(with(density) { rowHeightPx.toDp() })
            // 1.5dp 内缩 = 相邻卡之间 3dp 缝
            .padding(1.5.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
    ) {
        // 统一封面绘制：横图完整显示 + 上下模糊延伸（一个字都不切），
        // 竖图/方图按内容重心裁。宽卡的"左右留白"问题也一并没了 —— 横图不再被裁。
        CoverImage(
            url = track.artworkUrl,
            contentDescription = track.title,
            cardAspect = if (rowHeightPx > 0f) cardWidthPx / rowHeightPx else 1f,
            modifier = Modifier.fillMaxSize(),
            blurRadius = 26.dp,
            scrim = 0.40f
        )

        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.55f)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.38f),
                        1f to Color.Black.copy(alpha = 0.84f)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 7.dp)
        ) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.5.sp,
                lineHeight = 12.sp,
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
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                GlassIconButton(
                    icon = Icons.Rounded.PlayArrow,
                    onClick = onBall,
                    size = 40.dp,
                    iconSize = 22.dp,
                    hazeState = hazeState
                )
            }
        }
    }
}
