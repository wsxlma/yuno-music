package com.xiaoliu.yuno.feature.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.ui.component.LyricFont
import com.xiaoliu.yuno.feature.lyrics.render.LyricWaitingDots
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

// ============================================================================
// 【Apple Music 风格·竖屏歌词页】—— 2.0.0 逐字渲染重写
//
// 对照官方 Apple Music 6.5.2（REA 逆向）的真机制：
//   · 逐字高亮 = notifyWordHighlight(lineId, wordId, **duration**, isSung)
//     —— **每个字的点亮是带时长的渐变**，不是瞬间跳白。这就是
//     "从左到右渐渐亮起来"的真身。
//   · 已亮字用 LinkedHashSet 增量记录，**不重排整行**（不逐帧重算）。
//
// 2.0.0 根治的三个真机 bug（病根都在「词 vs 字」粒度混乱）：
//   bug1 逐字按词组整组亮  → 粒度改成**字**，一字一时间域
//   bug2 卡拉OK左→右渐亮丢失 → 恢复字内 reveal 渐变（不是跳白）
//   bug3 有些字唱到后消失    → 裁剪框不再可能反转（跨折行的字不裁剪）
//
// 性能约定（不变）：只有「当前行 / 唱过行」读 50ms 进度流，
// 未唱到的行走零状态 StaticLyricLine。
// ============================================================================

// 排版参数
private val LyricFontSize = 34.sp
/**
 * 句与句之间的竖向间距（每行上下各一份，所以实际句间距 = 本值 × 2）。
 *
 * 真机 Beta7：句间距收到原来的 4/7（去掉 3/7）—— 28dp → 16dp。
 */
private val LyricLineSpacing = 16.dp

internal const val UNSUNG_ALPHA = 0.20f     // 未唱字：偏黑灰，背景可透
private const val INACTIVE_ALPHA = 0.30f   // 非当前行
private const val BG_VOCAL_ALPHA = 0.55f   // 背景人声（x-bg）
private const val BG_FONT_SCALE = 0.82f

/**
 * 字放大终值（已唱字锁定值，「花开不谢」不回弹）。
 *
 * 真机 Beta7 微调：1.08 → **1.036**（用户给的目标值 1.0358 四舍五入）。
 * 行级歌词 [SyncedLine] 唱完保持的放大同步改成同一数值，两处观感一致。
 */
internal const val CHAR_SCALE = 1.036f
/** 长按进浮动歌词的判定时长（真机反馈：系统默认 ~500ms 太短易误触） */
private const val LONG_PRESS_MS = 700L

/**
 * 「等待球」在 LazyList 里占掉的下标数。
 *
 * 它永远是列表第 0 项（内容按条件塌陷/展开，但**占位常驻**），
 * 所以歌词行 i 在 LazyList 里是 i + DOTS_SLOT。滚动目标必须做这个换算，
 * 否则永远差一行 —— 真机 Beta10 的「当前句跑到第三行」就是这么来的。
 */
private const val DOTS_SLOT = 1

/** 等待球行高（dp）—— 与 LyricWaitingDots 内部保持一致，位置计算要用 */
private const val DOTS_ROW_DP = 52f
/** 放大后字间最小留白（px） */
private const val CHAR_GAP = 12f

// 真机 Beta7：字与字之间的左右间距略窄 → 整体放大 0.023 倍（×1.023）
private val KARAOKE_LETTER_SPACING = 2.046.sp
private val BG_LETTER_SPACING = 0.5115.sp

private val KaraokeStyle = TextStyle(
    fontSize = LyricFontSize,
    // 折行间距与静态行同一公式（收到 3/5），避免一句里动态/静态切换时行距跳变
    lineHeight = (LyricFontSize.value + (LyricFontSize.value * 0.32f + 12f) * 0.6f).sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = KARAOKE_LETTER_SPACING
)

private val LineLevelStyle = TextStyle(
    fontSize = LyricFontSize,
    lineHeight = (LyricFontSize.value * 1.25f + 12f).sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = BG_LETTER_SPACING
)

/**
 * 折行行高（真机 Beta7）：两行之间的**间距**收到原来的 3/5。
 *
 * 间距 = lineHeight − 字号；旧版间距偏大（见 [StaticLyricLine]），
 * 这里统一按 `字号 + 旧间距 × 0.6` 重算，逐字行与静态行都用它，
 * 避免同一句里动态/静态切换时行距跳变。
 */
private fun wrappedLineHeight(fontScale: Float): TextUnit {
    val fs = LyricFontSize.value * fontScale
    val oldLineHeight = LineLevelStyle.lineHeight.value * fontScale + 12f
    val oldGap = oldLineHeight - fs
    return (fs + oldGap * 0.6f).sp
}

// ============================================================================
// 字级模型 —— bug1 的根治点
// ============================================================================

/**
 * 一个字（渲染的最小单位），有**自己独立的时间域**。
 *
 * 1.0.75 的 `Word.text` 可以是词组（"AB"），一个 Word 一个格子一个点亮时刻
 * → 唱到 A 就连着 B 一起亮一起放大。这里把最小单位钉死成「字」，
 * 词只是字的分组，不再决定点亮粒度。
 */
data class LyricChar(
    val text: String,
    val startMs: Long,
    val endMs: Long
) {
    /** 字内进度 0..1 —— 卡拉 OK「从左到右渐渐亮」的唯一输入 */
    fun progressAt(posMs: Long): Float = when {
        posMs <= startMs -> 0f
        posMs >= endMs -> 1f
        else -> {
            val span = (endMs - startMs).coerceAtLeast(1L)
            ((posMs - startMs).toFloat() / span).coerceIn(0f, 1f)
        }
    }
}

/**
 * 把一行展开成**字序列**。
 *
 * 源数据只给词级时间戳时（Apple TTML 的 span / 网易云 YRC / 增强 LRC），
 * 在词内均分成字级 —— 这样「从左到右渐渐亮」在词内部也能逐字推进，
 * 不会退化成「词组一起亮」。
 */
fun expandChars(line: LyricLine): List<LyricChar> {
    val out = ArrayList<LyricChar>(line.words.sumOf { it.text.length })
    for (w in line.words) {
        val chars = w.text.toList()
        val n = chars.size
        if (n == 0) continue
        // 每个字至少 1ms，避免 0 时长导致 progress 恒 1（整组瞬间点亮）
        val endMs = maxOf(w.endMs, w.startMs + n)
        val span = endMs - w.startMs
        chars.forEachIndexed { i, ch ->
            val s = w.startMs + span * i / n
            val e = w.startMs + span * (i + 1) / n
            out += LyricChar(ch.toString(), s, maxOf(e, s + 1))
        }
    }
    return out
}

/** 单个字的格子（从整行布局读坐标，折行位置对齐） */
data class WordCell(
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    /** 该字是否**跨越折行** —— 跨行时裁剪矩形无法定义，必须整字不裁剪画 */
    val wraps: Boolean = false
) {
    /** 安全宽度：跨折行时返回 0，调用方据此跳过裁剪 */
    val safeWidth: Float get() = if (wraps) 0f else (right - left).coerceAtLeast(0f)
}

// ============================================================================
// 歌词列表
// ============================================================================

/**
 * 歌词列表。
 *
 * @param positionMs 播放进度（State 形式传入，实现"只有当前行重组"）
 * @param onLineClick 点击某行 → seek 到该行
 * @param onLineLongPress 长按某行 → 进入歌词聚焦
 * @param scrollPaused 聚焦态时冻结自动滚动
 * @param peekFraction 上一条"唱过、滚上去"的歌词露出比例（竖屏 3/4，横屏 1/3）
 * @param waitForLyrics 引擎侧等待态（歌词尚未到达）→ 等待球优先占位
 */
@Composable
fun LyricsView(
    timeline: LyricsTimeline,
    positionMs: State<Long>,
    modifier: Modifier = Modifier,
    onLineClick: ((LyricLine) -> Unit)? = null,
    onLineLongPress: ((LyricLine, Offset, Rect) -> Unit)? = null,
    scrollPaused: Boolean = false,
    focusProgress: Float = 0f,
    topInsetPx: Float = 0f,
    peekFraction: Float = 0.75f,
    waitForLyrics: Boolean = false
) {
    val listState = rememberLazyListState()
    val lines = remember(timeline) { timeline.allLines() }

    val currentIndex by remember(timeline) {
        derivedStateOf { timeline.indexOfLineAt(positionMs.value) }
    }
    val timeGap by remember(timeline) {
        derivedStateOf { timeline.isInGap(positionMs.value) }
    }
    // 等待球判据（Beta2 放宽）：只要还没到第一句就该显示等待球，
    // 不再要求"前奏 > 800ms" —— 否则短前奏的歌根本不出球。
    //
    // ⚠️ 必须走 derivedStateOf（Beta4 根治）：
    //    旧法在 LyricsView **顶层**读 positionMs.value，导致整个 LazyColumn
    //    每 50ms 全量重组 → 等待球的 LaunchedEffect(Unit) 每帧被重挂 →
    //    tMs 永远停在 0 → **点永远全透明**（行在、占位在，但看不见）。
    //    derivedStateOf 让它只在「布尔翻转」时通知，重组次数从每秒 20 次降到 2 次。
    val beforeFirstLine by remember(timeline) {
        derivedStateOf {
            val first = lines.firstOrNull()
            first != null && positionMs.value < first.startMs
        }
    }
    val waitingForLyrics = timeGap || waitForLyrics || beforeFirstLine

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val anchorPx = with(density) {
            if (topInsetPx > 0f) {
                // ⚠️ 锚点必须用**和行渲染同一套行高**（wrappedLineHeight）。
                //    Beta7 我改了行高却没改这里，锚点假设的行比实际高 →
                //    当前行顶不到锚点、上面留一大片空白、要到第三句才滚。
                val lineH = with(density) { wrappedLineHeight(1f).toDp() }
                val lineBox = lineH + LyricLineSpacing * 2
                val peek = lineBox * peekFraction.coerceIn(0f, 1f)
                (topInsetPx.toDp() + peek + 4.dp).toPx()
            } else {
                (maxHeight * 0.42f).toPx()
            }
        }.toInt()
        val topPad = with(density) { anchorPx.toFloat().toDp() }
        val topPadPx = anchorPx.toFloat()
        val bottomPad = with(density) { maxHeight * 0.52f }

        // 等待球**不进列表**（真机 Beta14 根治）：
        //   旧法把它当列表项插进歌词行里 → 跑到某句文字后面（"重複沉默 ●●●"）。
        //   现在它只是**标题栏与第一句之间那片空白里的覆盖层**，
        //   与歌词列表完全无关，绝不会挤进句子里，也不影响任何下标。
        //
        // 滚动目标因此回到最简：歌词行 i 就是 LazyList 的第 i 项。
        val scrollTarget = if (currentIndex < 0) 0 else currentIndex
        LaunchedEffect(scrollTarget, scrollPaused, topPad) {
            if (scrollPaused || lines.isEmpty()) return@LaunchedEffect
            runCatching { listState.animateScrollToItem(scrollTarget, 0) }
        }

        val lift = easeOutCubic(((focusProgress - 0.26f) / 0.74f).coerceIn(0f, 1f))
        val shrink = 1f - 0.12f * lift
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = shrink
                    scaleY = shrink
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad)
        ) {
            // ── 歌词行 ────────────────────────────────────────────────
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val isCurrent = index == currentIndex
                val wasLineCurrent = index <= currentIndex
                val focus by animateFloatAsState(
                    targetValue = if (isCurrent) 1f else 0f,
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    label = "lineFocus"
                )
                // 「花开不谢」的缩回系数（真机 Beta9 精确定义）：
                //   ① 字唱完 → 放大到 CHAR_SCALE
                //   ② **只要这一句还在播**（isCurrent）→ 保持放大，绝不缩回
                //   ③ 这一句成为过去时（换到下一句、开始滚上去）→ 随滚动**一起**缩回 1.0
                // 关键：触发条件是「本句不再是当前句」，不是字自身的进度。
                // 与 focus（透明度）分开建模，避免"字一放大就缩回去"的误伤。
                val lineZoom by animateFloatAsState(
                    targetValue = if (isCurrent) 1f else 0f,
                    animationSpec = tween(480, easing = FastOutSlowInEasing),
                    label = "lineZoom"
                )
                var rowRect by remember { mutableStateOf(Rect.Zero) }
                val rowModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = LyricLineSpacing)
                    .onGloballyPositioned { rowRect = it.boundsInRoot() }
                    .then(
                        if (onLineClick != null || onLineLongPress != null) {
                            // Beta4 手势修正：既要**足够久**（真机反馈 detectTapGestures 的
                            // ~500ms 太短、容易误触），又要**容忍轻微滑动**（Beta2 之前
                            // waitForUpOrCancellation 一动就取消 → 很难触发）。
                            // 所以自写：700ms 计时 + 2.5 倍滑动容差，两者都要。
                            Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .pointerInput(line) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val id = down.id
                                        // 超时 → 长按；提前抬手/滑出容差 → 单击
                                        val longPressed = withTimeoutOrNull(LONG_PRESS_MS) {
                                            var held = true
                                            while (held) {
                                                val ev = awaitPointerEvent()
                                                val ch = ev.changes.firstOrNull { it.id == id }
                                                if (ch == null || !ch.pressed) {
                                                    held = false
                                                } else {
                                                    val moved = (ch.position - down.position).getDistance()
                                                    if (moved > viewConfiguration.touchSlop * 2.5f) {
                                                        held = false   // 算滑动，放弃长按
                                                    }
                                                }
                                            }
                                            false
                                        } == true

                                        if (longPressed) {
                                            onLineLongPress?.invoke(
                                                line, rowRect.topLeft + down.position, rowRect
                                            )
                                        } else {
                                            onLineClick?.invoke(line)
                                        }
                                    }
                                }
                        } else Modifier
                    )

                when {
                    line.isBackground -> StaticLyricLine(
                        text = line.plainText, modifier = rowModifier,
                        alpha = BG_VOCAL_ALPHA, fontScale = BG_FONT_SCALE,
                        fontStyle = FontStyle.Italic, focus = focus
                    )

                    line.isLineLevel -> SyncedLine(
                        line = line, timeline = timeline, lineIndex = index,
                        wasLineCurrent = wasLineCurrent, focus = focus,
                        lineZoom = lineZoom,
                        positionMs = positionMs, modifier = rowModifier
                    )

                    else -> KaraokeLine(
                        line = line, timeline = timeline,
                        wasLineCurrent = wasLineCurrent, focus = focus,
                        lineZoom = lineZoom,
                        positionMs = positionMs, modifier = rowModifier
                    )
                }
            }
        }

        // ── 等待球：**标题栏与歌词之间的空白区覆盖层**（真机 Beta14）────────
        // 它不是列表项，所以永远不会挤进句子里、不影响任何行下标。
        // 位置：球心落在「标题栏 → 第一句歌词」这段距离 X 的 13/20 处
        //（= 正中间再稍微偏下一点）。
        if (waitingForLyrics && lines.isNotEmpty()) {
            val gapX = (topPadPx - topInsetPx).coerceAtLeast(1f)
            val dotsCenterY = topInsetPx + gapX * 13f / 20f
            val halfRow = DOTS_ROW_DP * density.density / 2f
            // 真机（2026-10-26）：逐颗渐亮要**铺满整个空档**，不许 1 秒内三颗全亮。
            // 时间窗 =「下一句开始 - 当前」，空档切换时重算一次，动画播放期间不重算
            //（记住 remember 只在 key 变化时重算，positionMs 的读取只是取值不算订阅）。
            val dotsSpanMs = remember(lines, waitingForLyrics) {
                val now = positionMs.value
                val next = lines.firstOrNull { it.startMs > now }?.startMs
                    ?: (now + 6000L)
                (next - now).coerceIn(3000L, 60_000L)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (dotsCenterY - halfRow).toInt()) }
            ) {
                LyricWaitingDots(Modifier.fillMaxWidth(), spanMs = dotsSpanMs)
            }
        }
    }
}

// ============================================================================
// KaraokeLine —— 字级逐字揭示（2.0.0 重写）
// ============================================================================

/**
 * 逐字卡拉 OK 行。
 *
 * **渲染契约**（与 Rust `yuno-lyrics-timeline::GlyphPaint` 一致）：
 *
 * | 字的状态 | reveal | scale | 画法 |
 * |---|---|---|---|
 * | 还没唱到 | 0 | 1.0 | 整字灰 |
 * | **正在唱** | 0→1 字内线性 | 1.0→1.08 | **从左到右切裁剪**，左半白右半灰 |
 * | 已唱完 | 1 | 1.08 | 整字白，锁定不回弹 |
 *
 * 关键差异（对 1.0.75）：
 *  * **一字一时间域**：`expandChars` 把词内均分成字级 → 不会「AB 一起亮」
 *  * **渐变不跳白**：正在唱的字按 reveal 切左到右的裁剪宽度
 *  * **裁剪不反转**：跨折行的字（`WordCell.wraps`）不裁剪整字画
 *  * **不重复绘制**：正在唱的字只画一次（1.0.75 画了两遍）
 */
@Composable
private fun KaraokeLine(
    line: LyricLine,
    timeline: LyricsTimeline,
    wasLineCurrent: Boolean,
    focus: Float,
    lineZoom: Float,
    positionMs: State<Long>,
    modifier: Modifier = Modifier
) {
    if (!wasLineCurrent) {
        StaticLyricLine(text = line.plainText, modifier = modifier, focus = focus)
        return
    }

    val pos = positionMs.value
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val text = line.plainText

    // 字级展开（缓存键带 timeline：同词同时间的行在不同曲目里身份不同）
    val chars = remember(line, timeline) { expandChars(line) }
    // 每个字在整行字符串里的首字符下标
    val prefix = remember(chars) {
        var acc = 0
        chars.map { c -> acc.also { acc += c.text.length } }.toIntArray()
    }

    BoxWithConstraints(modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }.toInt()
        val layoutWidthPx = (maxWidthPx * 0.92f).toInt()
        val styleForChars = KaraokeStyle.copy(fontFamily = LyricFont.currentFamily())

        // ⚠️ 攻击性检查（Beta14）：布局缓存必须把**字体**也算进 key，
        //    否则用户在设置里换歌词字体后 remember 命中旧缓存 → 不重排。
        val fontFamily = LyricFont.currentFamily()
        val textLayout = remember(text, layoutWidthPx, fontFamily) {
            textMeasurer.measure(
                text = AnnotatedString(text),
                style = styleForChars,
                constraints = Constraints(maxWidth = layoutWidthPx)
            )
        }
        val canvasHeight = with(density) { textLayout.size.height.toDp() }

        // 每字单独 TextLayout（只含该字）—— 画的时候只画那一个字，不叠整行
        val charLayouts = remember(text, layoutWidthPx, fontFamily) {
            chars.map { c ->
                textMeasurer.measure(
                    text = AnnotatedString(c.text),
                    style = styleForChars,
                    constraints = Constraints(maxWidth = 4096)
                )
            }
        }
        // 字格子（从整行布局读坐标，折行位置对齐）
        val cells = remember(textLayout, chars, prefix) {
            buildCharCells(textLayout, chars, prefix)
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                // 真机 Beta11：**唱完的句子要和没播的一样暗**。
                // 旧法 0.6 + 0.4×focus → 唱完停在 0.6，比未唱行（0.30）亮一倍。
                // 现在统一收敛到 INACTIVE_ALPHA，只在"是当前句"时亮起来。
                .graphicsLayer { alpha = INACTIVE_ALPHA + (1f - INACTIVE_ALPHA) * focus }
        ) {
            if (text.isEmpty()) return@Canvas

            for (i in chars.indices) {
                val cell = cells[i]
                val c = chars[i]
                val layout = charLayouts[i]
                val p = c.progressAt(pos)

                // 画字的位置：整行布局的字格左上角
                val at = Offset(cell.left, cell.top)

                // 暗底子（未唱 / 正在唱的未揭示部分）
                val dim = Color.White.copy(alpha = UNSUNG_ALPHA)

                // 字放大（真机 Beta9）：
                //   未唱 p=0        → 1.0
                //   正在唱 0<p<1    → 1.0 → CHAR_SCALE 随字内进度生长
                //   已唱 p=1        → CHAR_SCALE **锁定**（这就是"花开不谢"）
                // 然后整句乘 [lineZoom]：本句还在播 = 1（保持），
                // 本句成过去时 → 0（随滚动缩回原样）。
                val cs = { q: Float ->
                    val grown = if (q >= 1f) CHAR_SCALE else 1f + (CHAR_SCALE - 1f) * q
                    1f + (grown - 1f) * lineZoom
                }

                when {
                    // ── 已唱完：整字白 ──
                    p >= 1f -> drawChar(
                        this, layout, at, cell, Color.White, cs(1f), 1f, scalePivotY = cell.bottom
                    )

                    // ── 正在唱：**暗底子 + 白色从左到右扫**，同时拉伸 ──
                    p > 0f -> drawChar(
                        this, layout, at, cell, dim, cs(p), p,
                        scalePivotY = cell.bottom
                    )

                    // ── 还没唱到：整字暗底（必须看得见，不许隐形）──
                    else -> drawChar(
                        this, layout, at, cell, dim, cs(0f), 0f, scalePivotY = cell.bottom
                    )
                }
            }
        }
    }
}

/**
 * 画一个字：**底子 + 揭示层** 两层。
 *
 * 真机规格（2.0.0 Beta2 修正）：
 *   「应该有一个底子，播放这一句的时候填充渐亮同时拉伸，
 *     而不是播放到这一句直接消失掉，表演一个现场绘画」
 *
 * 所以：
 *  * **底子永远整字画**，不裁剪 —— 唱到之前看得见（暗），唱到时不会"消失再出现"
 *  * **揭示层**是白色，按 reveal 从左到右切裁剪宽度，叠在底子上扫过去
 *  * 两层共用同一个缩放变换（同时拉伸），所以看起来是"底子被填亮"而不是"重画"
 *
 * Beta1 的 bug：只画揭示层、不画底子，且 reveal=0 时裁剪宽度为 0
 * → 唱到之前整句隐形、唱到时才现场绘画。这就是你说的那个症状。
 */
internal fun drawChar(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    layout: TextLayoutResult,
    at: Offset,
    cell: WordCell,
    baseColor: Color,
    scale: Float,
    reveal: Float,
    scalePivotY: Float
) = with(scope) {
    // 跨折行的字裁剪矩形无法定义（right < left）→ 一律不裁剪整字画
    val canClip = !cell.wraps && cell.safeWidth > 0f

    val body: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
        // ① 底子：整字、不裁剪、永远在
        drawText(textLayoutResult = layout, color = baseColor, topLeft = at)

        // ② 揭示层：白色从左到右扫（只有正在唱的字才需要）
        //
        //    真机 Beta10：**跨折行的字**（一句分两行时第一行末尾那个）
        //    裁剪矩形无法定义（canClip=false），旧法两个分支都不进 →
        //    暗底一直挂着，直到 reveal≥1 突然整字变白 = "闪现一下整个字都亮起来"。
        //    这里改成：不能裁剪时**用 alpha 渐变代替裁剪**，同样是从暗到亮连续过渡。
        if (reveal > 0f && reveal < 1f) {
            if (canClip && cell.safeWidth > 0f) {
                // ── 漫反射边缘（真机 Beta12）────────────────────────────
                // 官方的渐亮是"光源扫过去"：亮区边界往右一段渐渐暗下去，
                // 不是一条垂直硬线。
                //
                // ⚠️ Beta11 我用 Brush.horizontalGradient 做，结果整个字
                // 一起亮（渐变采样空间和字的实际绘制范围没对齐）。
                // 现在退回 **clipRect**（Beta10 验证过是对的），
                // 用「多层切片递增 alpha」叠出柔边 —— 不依赖渐变坐标语义。
                val feather = 0.5f                    // 羽化宽度（占字宽）
                val edge = reveal.coerceIn(0f, 1f)
                val from = (edge - feather).coerceAtLeast(0f)
                val steps = 6
                for (k in 0..steps) {
                    val t = from + (edge - from) * k / steps   // 裁到哪
                    val a = (k.toFloat() / steps)             // 这一层多亮
                    if (a <= 0f) continue
                    val w = cell.safeWidth * t
                    clipRect(
                        cell.left - CHAR_GAP / 2f,
                        cell.top - CHAR_GAP / 2f,
                        cell.left + w + CHAR_GAP / 2f,
                        cell.bottom + CHAR_GAP / 2f
                    ) {
                        drawText(
                            textLayoutResult = layout,
                            color = Color.White.copy(alpha = a),
                            topLeft = at
                        )
                    }
                }
            } else {
                // 跨折行（裁剪矩形无法定义）：整字画，按 reveal 从透明渐到实白
                drawText(
                    textLayoutResult = layout,
                    color = Color.White.copy(alpha = reveal),
                    topLeft = at
                )
            }
        } else if (reveal >= 1f) {
            // 全亮：整字白（底子已是白，这里补一层保证盖满）
            drawText(textLayoutResult = layout, color = Color.White, topLeft = at)
        }
    }

    if (scale != 1f) {
        withTransform({
            translate(cell.left, scalePivotY)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-cell.left, -scalePivotY)
        }) { body() }
    } else {
        body()
    }
}

/**
 * 每个字的格子（与整行 textLayout 同宽约束，折行坐标一致）。
 *
 * **两个真机 bug 的共同根因**：
 *  1. 「唱到以后字消失」——字跨折行时 `right < left`，裁剪矩形反转画出空的。
 *  2. 「最后一个字叠到第一个字上」——字正好在折行处结束时，
 *     `end` 落在**下一行开头**（x=0），旧代码 `min(l, r)` 把 left 拉成 0，
 *     于是这个字被画到 x=0，正好压在该行第一个字上。
 *
 * 修法：**left 永远是字自己的起始 x，绝不参与 min**；
 * 只有起止落在同一行时才计算 right（供裁剪用），否则标 `wraps` 整字不裁剪。
 */
fun buildCharCells(
    textLayout: TextLayoutResult,
    chars: List<LyricChar>,
    prefix: IntArray
): List<WordCell> {
    val text = textLayout.layoutInput.text
    return chars.indices.map { i ->
        val start = prefix[i].coerceIn(0, (text.length - 1).coerceAtLeast(0))
        val end = (start + chars[i].text.length).coerceIn(start + 1, text.length.coerceAtLeast(start + 1))
        val lineStart = textLayout.getLineForOffset(start)
        val lineEnd = textLayout.getLineForOffset((end - 1).coerceAtLeast(start))
        val wraps = lineStart != lineEnd

        // 字的起始 x —— 这是唯一可信的横坐标，绝不动它
        val l = textLayout.getHorizontalPosition(start, usePrimaryDirection = true)
        // 只有起止同在一行时，end 的 x 才有意义；否则 end 落在下一行开头（x=0）会污染坐标
        val r = if (wraps) l else textLayout.getHorizontalPosition(end, usePrimaryDirection = true)

        WordCell(
            top = textLayout.getLineTop(lineStart).toFloat(),
            bottom = textLayout.getLineBottom(lineEnd).toFloat(),
            left = l,
            right = if (wraps) l else maxOf(l, r),
            wraps = wraps
        )
    }
}

// ============================================================================
// SyncedLine —— 行级歌词（普通 LRC / 无逐字降级）
// ============================================================================

@Composable
private fun SyncedLine(
    line: LyricLine,
    timeline: LyricsTimeline,
    lineIndex: Int,
    wasLineCurrent: Boolean,
    focus: Float,
    lineZoom: Float,
    positionMs: State<Long>,
    modifier: Modifier = Modifier
) {
    if (!wasLineCurrent) {
        StaticLyricLine(text = line.plainText, modifier = modifier, focus = focus)
        return
    }

    val pos = positionMs.value
    val progress = timeline.lineProgress(lineIndex, pos)
    val baseAlpha = 0.38f + 0.62f * progress
    Text(
        text = line.plainText,
        modifier = modifier
            .fillMaxWidth(0.92f)
            .graphicsLayer {
                this.alpha = (0.6f + 0.4f * focus) * (0.6f + 0.4f * baseAlpha)
                // 真机 Beta9「花开不谢」（与 KaraokeLine 同一规则）：
                //   唱过的部分保持放大，**本句还在播就不缩**；
                //   本句成过去时（lineZoom→0）才随滚动缩回 1.0。
                val grown = 1f + (CHAR_SCALE - 1f) * progress
                val s = 1f + (grown - 1f) * lineZoom
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 0.5f)
            },
        color = Color.White,
        fontSize = LyricFontSize,
        fontFamily = LyricFont.currentFamily(),
        lineHeight = LineLevelStyle.lineHeight,
        fontWeight = FontWeight.Medium,
        letterSpacing = BG_LETTER_SPACING
    )
}

// ============================================================================
// 静态歌词行（非当前行 —— 什么状态都不读）
// ============================================================================

@Composable
fun StaticLyricLine(
    text: String,
    modifier: Modifier = Modifier,
    alpha: Float = INACTIVE_ALPHA,
    fontScale: Float = 1f,
    fontStyle: FontStyle = FontStyle.Normal,
    focus: Float = 0f
) {
    Text(
        text = text,
        modifier = modifier.graphicsLayer { this.alpha = alpha + (1f - alpha) * focus },
        // 真机 Beta11：透明度**只在 graphicsLayer 上应用一次**。
        // 旧法 color 也乘一遍 alpha → 实际亮度 0.30×0.30 = 0.09，
        // 跟唱完的行（0.6）完全对不上，看上去"已唱的只是暗了一点点"。
        color = Color.White,
        fontSize = LyricFontSize * fontScale,
        fontFamily = LyricFont.currentFamily(),
        // 真机 Beta7：折行时两行之间太宽 → 间距收到原来的 3/5。
        // 旧公式 (LineLevelStyle.lineHeight × scale + 12f) 里 12f 被加了两次，
        // 字 34sp 时 lineHeight 达 66.5sp（间距 32.5sp）—— 这就是"过宽"的来源。
        lineHeight = wrappedLineHeight(fontScale),
        fontWeight = FontWeight.Light,
        fontStyle = fontStyle,
        letterSpacing = (BG_LETTER_SPACING.value * fontScale).sp
    )
}

private fun easeOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
