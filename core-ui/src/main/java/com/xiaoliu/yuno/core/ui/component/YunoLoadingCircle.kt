package com.xiaoliu.yuno.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================================
// 苹果风加载圈 —— 替换旧的"灰白塑料骨架屏"（真机反馈）：
// 一段带扫光渐变的旋转弧，白色细线、900ms 一圈，任何页面的加载态通用。
// ============================================================================

@Composable
fun YunoLoadingCircle(
    modifier: Modifier = Modifier,
    size: Dp = 30.dp,
    strokeWidth: Dp = 3.dp
) {
    val transition = rememberInfiniteTransition(label = "loadingCircle")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle"
    )

    Canvas(modifier.size(size)) {
        drawArc(
            brush = Brush.sweepGradient(
                listOf(
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.05f),
                    Color.White.copy(alpha = 0.95f),
                    Color.White.copy(alpha = 0.05f)
                )
            ),
            startAngle = angle,
            sweepAngle = 118f,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}