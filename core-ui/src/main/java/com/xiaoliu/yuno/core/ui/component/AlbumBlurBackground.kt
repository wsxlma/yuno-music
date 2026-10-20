package com.xiaoliu.yuno.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ======================= 【Apple Music 风格·仅歌词页使用】 =======================
// 真机反馈第三轮提速（2026-10-19）：
//   上版 13~21 秒周期被反馈"丝毫看不出变化"——颜料流速调到 5.5~8.5 秒，
//   同时加大漂移幅度：一眼能看出色团在缓慢呼吸流动。
//   · 不上封面图，纯 palette 主题色块（高饱和 = "没加水的颜料"）
//   · 多个大色团各自漂移、叠加混合
//   · 重度模糊（88dp）让色块融为一体，再叠压暗遮罩保证歌词可读

/**
 * 歌词页专用：专辑主题色「颜料流动」背景。
 *
 * @param artworkUrl 保留参数（调用方无需改），本实现纯 palette 着色不再加载封面
 * @param palette   封面色板（vibrant / light / dominant / muted 作四团颜料）
 * @param blurRadius 2026-10-19 耗电优化：88dp 重度模糊每帧全屏重算是耗电大头，
 *   降到 48dp —— 色团本就是大半径径向渐变（天然柔边），观感几乎不变
 * @param animated 显隐开关：大图沉浸模式时封面盖满全屏，流动动画纯白跑 → 上层传 false
 */
@Composable
fun AlbumBlurBackground(
    artworkUrl: String?,
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    scrimAlpha: Float = 0.26f,
    blurRadius: Dp = 48.dp,
    animated: Boolean = true
) {
    val transition = rememberInfiniteTransition(label = "paint")
    val t1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5_500, easing = LinearEasing), RepeatMode.Reverse),
        label = "t1"
    )
    val t2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "t2"
    )
    val t3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(8_500, easing = LinearEasing), RepeatMode.Reverse),
        label = "t3"
    )
    val a1 = if (animated) t1 else 0.5f
    val a2 = if (animated) t2 else 0.5f
    val a3 = if (animated) t3 else 0.5f

    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(blurRadius)
        ) {
            val w = size.width
            val h = size.height

            // 深色底
            drawRect(palette.dark.blend(Color.Black, 0.35f))

            // 三团"浓颜料"（原四团）：高饱和、大半径、各走各的漂移轨道。
            // 砍掉 muted 团 —— 每帧少一次全屏径向渐变光栅化，耗电优化的一部分
            blob(
                color = glow(palette.vibrant),
                center = Offset(w * (0.18f + 0.42f * a1), h * (0.24f + 0.26f * a2)),
                radius = w * 0.95f,
                alpha = 0.90f
            )
            blob(
                color = glow(palette.light),
                center = Offset(w * (0.86f - 0.38f * a2), h * (0.14f + 0.34f * a1)),
                radius = w * 0.80f,
                alpha = 0.75f
            )
            blob(
                color = glow(palette.dominant),
                center = Offset(w * (0.66f - 0.36f * a3), h * (0.68f + 0.20f * a1)),
                radius = w * 1.05f,
                alpha = 0.85f
            )

            // 底部线性压暗：给控制栏留阅读深度
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.dark.blend(Color.Black, 0.45f).copy(alpha = 0.85f)
                    ),
                    startY = h * 0.55f
                )
            )
        }

        // 压暗遮罩（保证歌词可读）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        )

        // 极轻噪点
        NoiseOverlay(Modifier.fillMaxSize(), intensity = 0.30f)
    }
}

/** 高饱和提纯 = 颜料质感（"没加水的颜料"） */
private fun glow(c: Color): Color = c.boostSaturation(saturationFactor = 1.5f, valueFactor = 1.05f)

private fun DrawScope.blob(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * 0.55f),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

/** 简单颜色混合 */
private fun Color.blend(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = alpha + (other.alpha - alpha) * t
)
