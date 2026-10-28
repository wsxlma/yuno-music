package com.xiaoliu.yuno.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.ui.component.LyricFont

/**
 * 单行逐字卡拉 OK 渲染（横屏滚筒共用；竖屏还是 LyricsView 里的 KaraokeLine）。
 *
 * 渲染契约与 KaraokeLine 完全一致（见 LyricsView.kt 的契约表）：
 *   未唱 = 灰底整字；正在唱 = 灰底 + 白色从左到右扫 + 字内拉伸；
 *   已唱 = 整字白、放大锁定。
 *
 * 与 KaraokeLine 的差异：**不画行级效果**（focus 亮度 / lineZoom 回缩）。
 * 横屏轮行滚动有自己的全局缩放 / 亮度 / 模糊（graphicsLayer），由调用方施加；
 * 「花开不谢」在横屏的行级语义 =「本句还是不是当前句」，同样由调用方判定。
 */
@Composable
fun KaraokeSingLine(
    line: LyricLine,
    positionMs: State<Long>,
    style: TextStyle,
    maxWidthPx: Int,
    maxLines: Int = 3,
    modifier: Modifier = Modifier
) {
    val pos = positionMs.value
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val text = line.plainText
    val fontFamily = style.fontFamily ?: LyricFont.currentFamily()

    // 字级展开（词内均分成字级时间域 —— 与竖屏同一函数，行为一致）
    val chars = remember(line) { expandChars(line) }
    val prefix = remember(chars) {
        var acc = 0
        chars.map { c -> acc.also { acc += c.text.length } }.toIntArray()
    }

    // 整行布局：约束与滚筒测高时一致（同一 style / maxWidth / maxLines），
    // 折行坐标才不会漂。
    val textLayout = remember(text, maxWidthPx, fontFamily) {
        textMeasurer.measure(
            text = AnnotatedString(text),
            style = style.copy(fontFamily = fontFamily),
            constraints = Constraints(maxWidth = maxWidthPx),
            maxLines = maxLines
        )
    }
    val charLayouts = remember(text, fontFamily) {
        chars.map { c ->
            textMeasurer.measure(
                text = AnnotatedString(c.text),
                style = style.copy(fontFamily = fontFamily),
                constraints = Constraints(maxWidth = 4096)
            )
        }
    }
    // 字格子（从整行布局读坐标，折行位置对齐 —— 与竖屏同源）
    val cells = remember(textLayout, chars, prefix) {
        buildCharCells(textLayout, chars, prefix)
    }

    val canvasHeight = with(density) { textLayout.size.height.toDp() }

    Canvas(
        Modifier
            .fillMaxWidth(0.87f)
            .height(canvasHeight)
            .then(modifier)
    ) {
        if (text.isEmpty()) return@Canvas

        for (i in chars.indices) {
            val cell = cells[i]
            val c = chars[i]
            val layout = charLayouts[i]
            val p = c.progressAt(pos)
            val at = Offset(cell.left, cell.top)
            val dim = Color.White.copy(alpha = UNSUNG_ALPHA)

            // 字放大：正在唱 1.0 → CHAR_SCALE 随字内进度生长，已唱锁定。
            val cs = { q: Float ->
                if (q >= 1f) CHAR_SCALE else 1f + (CHAR_SCALE - 1f) * q
            }

            // 光晕层改走 WebView（方法 B），Canvas 只画底子 + 揭示
            when {
                p >= 1f -> drawChar(
                    this, layout, at, cell, Color.White,
                    cs(1f), 1f, scalePivotY = cell.bottom
                )
                p > 0f -> drawChar(
                    this, layout, at, cell, dim,
                    cs(p), p, scalePivotY = cell.bottom
                )
                else -> drawChar(
                    this, layout, at, cell, dim,
                    cs(0f), 0f, scalePivotY = cell.bottom
                )
            }
        }
    }
}