package com.xiaoliu.yuno.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.model.Word
import com.xiaoliu.yuno.core.ui.component.LyricFont
import kotlin.math.sin

// ============================================================================
// 沉浸式浮动歌词 —— 第八轮重构（真机四个反馈全收）：
//   1. 进入太快：长按判定拉到 700ms（见 LyricsView），且时间轴重排——
//      水波纹**完整播完**（前 55% 进度）才开始浮起，
//      浮定后（75%）单句才登场，不再"波纹还没播完就切全屏"。
//   2. 水波纹重做：椭圆挤压随时间"渐归正圆"（水波从压扁自然散开），
//      双环错峰衰减，更像真实水面。
//   3. 浮动句跟随播放：内容 = 实时 currentLine（timeline.indexOfLineAt(posMs)），
//      唱完一句自动换下一句，不再固定为进入时那一句。
//   4. 竖屏全屏 + 一次一句 + 每字独立图层：
//      每字按「逐字时间戳 + 种子动画参数」独立 clip + withTransform，
//      新唱到的字"出生浮起"（scale 0.72→1），已唱字带相位错开的呼吸漂浮，
//      未唱字压灰 —— 用 transform 不用 font-size。
// ============================================================================

/** 一次聚焦请求：波纹起点（按下点、原行边界）——只做定位，句子内容实时跟播放走 */
data class LyricFocusRequest(
    val line: LyricLine,
    val press: Offset,
    val rowRect: Rect
)

@Composable
fun LyricFocusOverlay(
    timeline: LyricsTimeline?,
    request: LyricFocusRequest?,
    progress: Float,
    topBarBottomPx: Float,
    positionMs: State<Long>,
    active: Boolean,
    onDismiss: () -> Unit,
    accentColor: Color = Color(0xFF8B7BF7),
    modifier: Modifier = Modifier
) {
    val state = request ?: return
    if (progress <= 0.001f) return

    // 实时当前行（真机：浮动句跟随播放行，唱完换下一句）
    val posMs = positionMs.value
    val currentLine: LyricLine? = timeline?.let {
        val idx = it.indexOfLineAt(posMs)
        if (idx >= 0) it.lineAt(idx) else null
    }
    val text = currentLine?.plainText.orEmpty()

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    // 单句布局（全屏可用宽度的 90% 居中；text 变化时重测）
    BoxWithConstraints(modifier.pointerInput(Unit) { detectTapGestures { onDismiss() } }) {
        val sizeW = constraints.maxWidth.toFloat()
        val sizeH = constraints.maxHeight.toFloat()
        val layoutWidthPx = (sizeW * 0.90f).toInt()

        val layout: TextLayoutResult? = remember(text, layoutWidthPx) {
            if (text.isEmpty()) null else textMeasurer.measure(
                text = AnnotatedString(text),
                style = FocusBaseStyle.copy(fontFamily = LyricFont.currentFamily()),
                constraints = Constraints(maxWidth = layoutWidthPx)
            )
        }

        // —— 时间轴映射（重排：波纹完整 → 浮起 → 单句定格）——
        val p = progress
        val dim = smooth(p, 0.08f, 0.55f)           // 全屏暗幕
        val rippleT = smooth(p, 0.02f, 0.60f)       // 波纹总进度
        val rippleFade = 1f - smooth(p, 0.42f, 0.62f) // 波纹收尾淡出
        val squish = smooth(p, 0.02f, 0.75f)         // 椭圆渐归正圆
        val lift = easeOutCubic(((p - 0.45f) / 0.40f).coerceIn(0f, 1f)) // 浮起
        val settle = smooth(p, 0.72f, 1f)            // 单句 alpha 定格

        // 浮起几何：原行中心 → 全屏中心（顶部留出标题栏高度）
        val sx = sizeW / 2f
        val sy = sizeH / 2f
        // 卡片几何（真机规格 Beta4：卡片紧贴标题栏正下方，不再屏幕居中）
        val cardPadH = with(density) { 22.dp.toPx() }
        val cardPadV = with(density) { 18.dp.toPx() }
        val textH = layout?.size?.height?.toFloat() ?: 0f
        val cardH = textH + cardPadV * 2f
        val cardW = layoutWidthPx.toFloat() + cardPadH * 2f
        val targetCenter = Offset(sx, topBarBottomPx + with(density) { 20.dp.toPx() } + cardH / 2f)
        val startCenter = Offset(
            state.rowRect.left + state.rowRect.width / 2f,
            state.rowRect.top + state.rowRect.height / 2f
        )
        val floatCenter = lerp(startCenter, targetCenter, lift)
        val floatAlpha = 0.35f + 0.65f * settle

        // 逐字动画参数（种子按字文本生成：节奏/语义差异 → 每个字自己的相位与幅度）
        val words = currentLine?.words.orEmpty()
        val prefix = remember(words) { wordPrefixOffsets(words) }
        val seeds = remember(words) { words.map { w -> wordSeed(w) } }
        // 1.0.72 "字本身上色"：每字单独一个 TextLayout（只含该字文本），
        // 画白字时只画那一个字，不叠另一层整行白字（旧法 → 大白块）。
        val wordLayouts = remember(words, layoutWidthPx) {
            words.map { w ->
                textMeasurer.measure(
                    text = AnnotatedString(w.text),
                    style = FocusBaseStyle.copy(fontFamily = LyricFont.currentFamily()),
                    constraints = Constraints(maxWidth = 4096)
                )
            }
        }
        // 字格子（从整行 layout 读坐标，折行对齐；按索引，不用 indexOf）
        val wordCells = remember(layout, words, prefix) {
            layout?.let { l ->
                val text = l.layoutInput.text
                words.indices.map { i ->
                    val start = prefix[i].coerceIn(0, text.length - 1)
                    val end = (start + words[i].text.length).coerceIn(start + 1, text.length)
                    val ln = l.getLineForOffset(start)
                    WordCell(
                        top = l.getLineTop(ln).toFloat(),
                        bottom = l.getLineBottom(ln).toFloat(),
                        left = l.getHorizontalPosition(start, usePrimaryDirection = true),
                        right = l.getHorizontalPosition(end, usePrimaryDirection = true)
                    )
                }
            }.orEmpty()
        }

        Canvas(Modifier.fillMaxSize()) {
            // 1) 全屏暗幕（原行矩形区域不挖孔，浮起后整屏压暗）
            val shadowA = 0.78f * dim
            if (shadowA > 0.01f) {
                drawRect(Color.Black.copy(alpha = shadowA))
            }

            // 2) 水波纹（原行中心）—— 双环错峰 + 椭圆渐归正圆
            val rippleCenter = Offset(
                state.rowRect.left + state.rowRect.width / 2f,
                state.rowRect.top + state.rowRect.height / 2f
            )
            val maxR = state.rowRect.width * 0.85f
            if (rippleFade > 0.02f) {
                repeat(3) { i ->
                    val t = ((rippleT * 1.35f - i * 0.22f)).coerceIn(0f, 1f)
                    if (t in 0.01f..0.99f) {
                        drawRippleNatural(
                            center = rippleCenter,
                            maxRadius = maxR,
                            t = t,
                            strength = (1f - t) * rippleFade,
                            squish = 0.55f + 0.45f * squish
                        )
                    }
                }
            }

            // 2.5) 浮动卡片（真机规格 Beta4）
            //      反色灯带 → **实底压住底下歌词（杜绝鬼影）** → 液态玻璃质感
            if (lift > 0.01f && cardW > 0f && cardH > 0f) {
                val cx = floatCenter.x
                val cy = floatCenter.y
                val corner = CornerRadius(28.dp.toPx())
                val cardTopLeft = Offset(cx - cardW / 2f, cy - cardH / 2f)
                val cardSize = Size(cardW, cardH)

                // 灯带颜色 = 专辑主题色的**反色**
                val inv = Color(
                    1f - accentColor.red,
                    1f - accentColor.green,
                    1f - accentColor.blue
                )

                // ① 灯带：多圈递减描边向外扩散，照亮范围比全屏专辑灯带更大
                repeat(7) { i ->
                    val spread = (i + 1) * 7.dp.toPx()
                    drawRoundRect(
                        color = inv.copy(alpha = (0.26f / (i + 1)) * lift),
                        topLeft = Offset(cardTopLeft.x - spread, cardTopLeft.y - spread),
                        size = Size(cardSize.width + spread * 2f, cardSize.height + spread * 2f),
                        cornerRadius = CornerRadius(corner.x + spread),
                        style = Stroke(7.dp.toPx())
                    )
                }

                // ② 实底：**不透**，把底下的歌词完全压住（杜绝鬼影）
                drawRoundRect(
                    color = Color(0xF2141420).copy(alpha = 0.97f * lift),
                    topLeft = cardTopLeft,
                    size = cardSize,
                    cornerRadius = corner
                )

                // ③ 液态玻璃质感：上浅下深微渐变 + 细高光描边
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.12f * lift),
                        0.55f to Color.White.copy(alpha = 0.05f * lift),
                        1f to Color.White.copy(alpha = 0.015f * lift)
                    ),
                    topLeft = cardTopLeft,
                    size = cardSize,
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.20f * lift),
                    topLeft = cardTopLeft,
                    size = cardSize,
                    cornerRadius = corner,
                    style = Stroke(1.dp.toPx())
                )

                // ④ 贴卡一圈亮边（灯带内圈）
                drawRoundRect(
                    color = inv.copy(alpha = 0.50f * lift),
                    topLeft = cardTopLeft,
                    size = cardSize,
                    cornerRadius = corner,
                    style = Stroke(2.dp.toPx())
                )
            }

            // 3) 原行圆角描边（浮起时随 lift 淡出）
            val rowFade = (1f - lift).coerceIn(0f, 1f)
            if (rowFade > 0.02f) {
                val rowCorner = CornerRadius(18.dp.toPx())
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.05f * rippleFade * rowFade),
                    topLeft = state.rowRect.topLeft,
                    size = state.rowRect.size,
                    cornerRadius = rowCorner
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.35f * rippleFade * rowFade),
                    topLeft = state.rowRect.topLeft,
                    size = state.rowRect.size,
                    cornerRadius = rowCorner,
                    style = Stroke(1.5.dp.toPx())
                )
            }

            // 4) 单句：每字独立图层（浮起跟随 + 出生弹跳 + 呼吸相位）
            if (layout != null && settle > 0.01f) {
                val lw = layout.size.width.toFloat()
                val lh = layout.size.height.toFloat()
                val center = floatCenter
                withTransform({
                    translate(center.x, center.y)
                    // 浮起期间从句子的原大小 0.82 → 1
                    scale(0.82f + 0.18f * lift, 0.82f + 0.18f * lift)
                    translate(-lw / 2f, -lh / 2f)
                }) {
                    // currentLine 在播放中途切歌/seek 时可能变 null（timeline 换了新行数据），
                    // 用 safe 调用避免 NPE 闪退（真机 bug：放歌放一半闪退）
                    val safeWordIndex = if (words.isEmpty()) -1
                        else currentLine?.let { timeline?.wordIndexAt(it, posMs) } ?: -1
                    val safeWordProgress = if (words.isEmpty()) 0f
                        else currentLine?.let {
                            timeline?.wordProgress(it, timeline.wordIndexAt(it, posMs), posMs)
                        } ?: 0f
                    drawWordByWord(
                        layout = layout,
                        words = words,
                        wordLayouts = wordLayouts,
                        wordCells = wordCells,
                        seeds = seeds,
                        posMs = posMs,
                        wordIndex = safeWordIndex,
                        wordProgress = safeWordProgress,
                        settle = settle,
                        floatAlpha = floatAlpha
                    )
                }
            }
        }
    }
}

// ---------------- 逐字渲染（每字独立 clip + transform） ----------------

/** 每字的动画种子参数：文本哈希驱动（同样文本稳定，不同字不同戏） */
private data class WordSeed(val phase: Float, val breathe: Float)

private fun wordSeed(w: Word): WordSeed {
    val h = (w.text.hashCode() and 0x7FFFFFFF).toFloat()
    return WordSeed(
        phase = h % 6.283f,
        breathe = 0.6f + (h % 1000) / 1000f * 0.8f   // 呼吸幅度 0.6~1.4
    )
}

private fun wordPrefixOffsets(words: List<Word>): IntArray {
    val arr = IntArray(words.size)
    var acc = 0
    words.forEachIndexed { i, w -> arr[i] = acc; acc += w.text.length }
    return arr
}

/**
 * 每字独立变换 + 单字 TextLayout（1.0.72 "字本身上色"）：
 *   · 未唱字：单字 layout 画灰 0.32（只画该字，不叠整行白层）
 *   · 已唱字：单字 layout 画纯白，1.05 保持 + 呼吸漂浮（浮歌词专属活感）
 *   · 当前字：单字灰底 + 白 mask 从左到右渐进（字内进度），1.0 不放大
 * 每字只画自己（单字 TextLayout）→ 无整行白层叠出来 → 大白块根除；
 * 白层就是该字本身（无灰底残留）→ 鬼影根除。
 * 全部用 withTransform（canvas transform，非 font-size 动画）。
 */
private fun DrawScope.drawWordByWord(
    layout: TextLayoutResult,
    words: List<Word>,
    wordLayouts: List<TextLayoutResult>,
    wordCells: List<WordCell>,
    seeds: List<WordSeed>,
    posMs: Long,
    wordIndex: Int,
    wordProgress: Float,
    settle: Float,
    floatAlpha: Float
) {
    if (words.isEmpty() || wordLayouts.isEmpty() || wordCells.isEmpty()) return

    for (i in words.indices) {
        val cell = wordCells[i]
        val layout = wordLayouts[i]
        val topLeft = Offset(cell.left, cell.top)

        when {
            i > wordIndex -> {
                // 未唱：灰（单字，原尺寸，无变换）
                drawText(
                    textLayoutResult = layout,
                    color = Color.White.copy(alpha = 0.32f * floatAlpha),
                    topLeft = topLeft
                )
            }

            i == wordIndex -> {
                // 当前字：1.0 不放大（与逐字行同一逻辑），只切白揭示 + 微幅呼吸
                val p = wordProgress.coerceIn(0f, 1f)
                val seed = seeds[i]
                val breatheScale = 1f + seed.breathe * 0.015f * sin(posMs * 0.0021f + seed.phase)
                val maskX = cell.left + (cell.right - cell.left) * p
                withTransform({
                    translate(cell.left, cell.bottom)   // 锚点 = 字左下角（保持 1.0）
                    scale(breatheScale, breatheScale, pivot = Offset.Zero)
                    translate(-cell.left, -cell.bottom)
                }) {
                    // 灰底（单字，同变换 → 与白字对齐）
                    drawText(
                        textLayoutResult = layout,
                        color = Color.White.copy(alpha = 0.32f * floatAlpha),
                        topLeft = topLeft
                    )
                    // 白揭示（从左到右切出已唱部分，单字）
                    val s = breatheScale
                    val revealX = (maskX - cell.left) * s + cell.left
                    clipRect(cell.left, cell.top, revealX.coerceIn(cell.left, cell.right), cell.bottom) {
                        drawText(
                            textLayoutResult = layout,
                            color = Color.White.copy(alpha = floatAlpha),
                            topLeft = topLeft
                        )
                    }
                }
            }

            else -> {
                // 已唱：1.05 全画（单字，无整行叠层）+ 呼吸漂浮（浮歌词专属）
                val seed = seeds[i]
                val dy = seed.breathe * 2.2f * sin(posMs * 0.0021f + seed.phase)
                val breatheScale = 1.05f +
                    seed.breathe * 0.015f * sin(posMs * 0.0021f + seed.phase)
                withTransform({
                    translate(cell.left, cell.bottom)   // 锚点 = 字左下角（向右上保持 1.05）
                    scale(breatheScale, breatheScale, pivot = Offset.Zero)
                    translate(-cell.left, -cell.bottom)
                    translate(0f, dy)          // 漂浮在缩放后叠加（不受 scale 影响）
                }) {
                    drawText(
                        textLayoutResult = layout,
                        color = Color.White.copy(alpha = floatAlpha),
                        topLeft = topLeft
                    )
                }
            }
        }
    }
}

// ---------------- 水波纹（自然化重做） ----------------

/**
 * 单圈水波：椭圆环描边，y 轴挤压随 [squish] 渐归正圆 + 双层环错峰。
 *   · 主环（2.5dp，alpha 0.40·strength）在前
 *   · 次环（1.5dp，alpha 0.20·strength）落后半拍
 * 更像真实水面：泛起时压扁，随后均匀散开成圆。
 */
private fun DrawScope.drawRippleNatural(
    center: Offset,
    maxRadius: Float,
    t: Float,
    strength: Float,
    squish: Float
) {
    if (t <= 0f || t >= 1f || strength <= 0f) return
    val r = easeOutCubic(t) * maxRadius
    val band = 5.dp.toPx()
    // 主环
    val mainR = r
    drawOval(
        color = Color.White.copy(alpha = 0.40f * strength),
        topLeft = Offset(center.x - mainR, center.y - mainR * squish),
        size = Size(mainR * 2f, mainR * 2f * squish),
        style = Stroke(2.5.dp.toPx())
    )
    // 次环（落后、更淡）
    val subR = (r - band * 1.6f).coerceAtLeast(1f)
    drawOval(
        color = Color.White.copy(alpha = 0.20f * strength),
        topLeft = Offset(center.x - subR, center.y - subR * squish),
        size = Size(subR * 2f, subR * 2f * squish),
        style = Stroke(1.5.dp.toPx())
    )
}

// ---------------- 缓动工具 ----------------

private fun smooth(x: Float, start: Float, end: Float): Float {
    val t = ((x - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun easeOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset =
    Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** 焦点句基础样式（34sp，与歌词行一致） */
private val FocusBaseStyle = TextStyle(
    fontSize = 34.sp,
    lineHeight = 46.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.5.sp
)