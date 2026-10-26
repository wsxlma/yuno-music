package com.xiaoliu.yuno.feature.player

import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.LyricsTimeline
import com.xiaoliu.yuno.core.ui.component.LyricFont
import com.xiaoliu.yuno.feature.lyrics.render.LyricWaitingDots
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================================
// 滚筒歌词（共享件）：当前行最大（左侧原点放大、向左延伸），已唱行越往上越小，
// 未唱行更小更暗。手机横屏全屏播放器与 TV「正在播放」页共用同一套滚筒。
// 2.1.1 起当前句走 KaraokeSingLine 逐字渲染（真机需求：横屏也有逐字）。
// ============================================================================

// ── 横屏滚筒歌词的排版常量（真机规格 Beta5）──────────────
/** 当前行缩放 */
private const val CURRENT_LINE_SCALE = 1.14f
/**
 * 下一句缩放 —— 真机确认这个大小合适，**不要动**。
 * 下下句 = 本值 × 0.9，之后每行再 ×0.9（几何衰减）。
 */
private const val NEXT_LINE_SCALE = 0.88f

/** 非当前行统一暗档（真机 v2：非常暗、非常通透） */
private const val INACTIVE_LINE_ALPHA = 0.18f

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WheelLyrics(
    timeline: LyricsTimeline,
    positionMs: State<Long>,
    onLineClick: ((LyricLine) -> Unit)? = null,
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
            // 逐颗渐亮铺满整个空档（竖屏同规格），谱次切换时重算一次
            val dotsSpanMs = remember(lines, waitingForLyrics) {
                val now = positionMs.value
                val next = lines.firstOrNull { it.startMs > now }?.startMs
                    ?: (now + 6000L)
                (next - now).coerceIn(3000L, 60_000L)
            }
            Box(
                Modifier.offset { IntOffset(0, dotsY.roundToInt()) }
            ) {
                LyricWaitingDots(spanMs = dotsSpanMs)
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
            // 亮度（真机 v2）：**只此两档** —— 当前句亮 1.0；其余一律同档暗
            //（下一句/已唱完/前奏前的所有行一视同仁）。
            // 旧法按距离渐变（d=1 给 0.45），前奏时第 0 行 d=0 被判成"当前行"
            // 全亮纯文本，唱到时才换逐字渲染 → 视觉上"先暗下去再重新亮起来"，
            // 且"下一句偏亮、轮到它时再暗下去重亮" —— 两档制一并根治。
            val alpha = if (i == currentIndex) 1f else INACTIVE_LINE_ALPHA
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