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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================================
// 面积加权「颜料流动」背景（2026-10-28 用户两轮钦定）
//
// 与旧版 AlbumBlurBackground 的区别：
//   · 旧版：固定取 vibrant/light/dominant 三个角色色，各画一团 ——
//     封面里面积很大的颜色可能完全不出现在背景上
//   · 新版：ArtworkPalette.swatches 是原封面全部色块按面积加权的列表
//     （扩展：weight sum = 1，大面积颜色权重高），背景按权重大小铺色 ——
//     面积越大的颜色，背景占的面积越大；每一种颜色都出现，只是占比不同
//   · 色团半径 ∝ sqrt(weight)（面积 ∝ weight），Screen 混合让颜色互相渗透，
//     呈现"迷幻"的混色效果
//   · 流动（2.6.x 修正）：色团数量 = 2×色板数（每色两团相位错开），
//     各团 Lissajous 轨迹（非往复、互不锁相）——"从一片比屏幕大的水域里
//     截取屏幕大小"的观感，而非"屏幕这么大的一滩水左右来回扫"
//   · 速度（2.6.4 用户第三次修正）：基线 9s 太慢 → 定 3.8s 一轮；
//     同时加浓稠度（色团更大更实、模糊 40dp→26dp、饱和度 1.25→1.38）
// ============================================================================

/**
 * 面积加权流动背景。
 *
 * @param palette      封面色板（用 [ArtworkPalette.flowingSwatches] 取加权色块）
 * @param animated     false 时静止（大图沉浸/省电场景）
 * @param speedFactor  流动速度倍率（默认 1.0 = 慢速洋流；>1 更快）
 * @param scrimAlpha   压暗遮罩强度（保证歌词可读性）
 * @param blurRadius   整层模糊（颜料自然柔边，不用太高）
 */
@Composable
fun FlowingPaletteBackground(
    palette: ArtworkPalette,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    speedFactor: Float = 1.0f,
    scrimAlpha: Float = 0.30f,
    blurRadius: Dp = 26.dp
) {
    val swatches = palette.flowingSwatches()

    // 每个通道独立的 Lissajous 相速（非 1.0 的不可公比方 → 轨迹永不重复、不锁相）
    val blobCount = swatches.size * 2
    val freqX = remember(blobCount) {
        FloatArray(blobCount) { 0.62f + 0.37f * ((it * 29) % 7) / 6f }
    }
    val freqY = remember(blobCount) {
        FloatArray(blobCount) { 0.41f + 0.29f * ((it * 17) % 5) / 4f }
    }

    val transition = rememberInfiniteTransition(label = "weightedPaint")
    val baseDuration = (3800f / speedFactor).toInt().coerceAtLeast(2000)
    val t1 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(baseDuration, easing = LinearEasing), RepeatMode.Reverse),
        label = "wp1"
    )
    val t2 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((baseDuration * 1.35f).toInt(), easing = LinearEasing), RepeatMode.Reverse),
        label = "wp2"
    )
    val t3 by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween((baseDuration * 1.7f).toInt(), easing = LinearEasing), RepeatMode.Reverse),
        label = "wp3"
    )
    val a1 = if (animated) t1 else 0.5f
    val a2 = if (animated) t2 else 0.5f
    val a3 = if (animated) t3 else 0.5f

    // 2π = 一个 Lissajous 相周期；各团相位 = 黄金角散布（互不重叠在同相）
    val twoPi = (2.0 * Math.PI).toFloat()
    val phase = remember(blobCount) {
        FloatArray(blobCount) { it * 2.39996f }
    }

    Box(modifier.clipToBounds()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .blur(blurRadius)
        ) {
            val w = size.width
            val h = size.height
            val maxDim = maxOf(w, h)

            // 深色底：取色板暗色再压暗，给色团一个统一的黑底
            drawRect(palette.dark.blendWith(Color.Black, 0.55f))

            // 每色铺 2 团（相位错开），色团半径 ∝ sqrt(weight)（面积 ∝ 权重）。
            // Lissajous 漂移：X/Y 各自是不同非锁相频率的叠加，轨道永不重复，
            // 也没有"从左到右再回来"的往复感。
            swatches.forEachIndexed { colorIndex, swatch ->
                val weight = swatch.weight.coerceIn(0.005f, 1f)
                repeat(2) { k ->
                    val i = colorIndex * 2 + k
                    val radiusScale = (0.62f + 1.30f * sqrt(weight)) * if (k == 0) 1f else 0.82f
                    val radius = maxDim * radiusScale

                    val px = phase[i]
                    val fx = freqX[i]
                    val fy = freqY[i]
                    val driftX = 0.18f * sin(twoPi * a1 * fx + px).toFloat() +
                        0.10f * sin(twoPi * a3 * (fx * 0.61f) + px * 1.7f).toFloat()
                    val driftY = 0.18f * cos(twoPi * a2 * fy + px * 1.3f).toFloat() +
                        0.10f * cos(twoPi * a3 * (fy * 0.73f) + px * 0.8f).toFloat()

                    val cx = w * (0.5f + driftX)
                    val cy = h * (0.5f + driftY)

                    // 色团本身提高一点饱和度（"颜料"感），alpha 按权重轻微加厚
                    val color = swatch.color.boostSaturation(1.18f, 1.04f)
                    val alpha = ((0.70f + 0.30f * weight) * if (k == 0) 1f else 0.88f)
                        .coerceIn(0.45f, 1f)

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = alpha),
                                color.copy(alpha = alpha * 0.5f),
                                Color.Transparent
                            ),
                            center = Offset(cx, cy),
                            radius = radius
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                        blendMode = androidx.compose.ui.graphics.BlendMode.Screen
                    )
                }
            }

            // 底部线性压暗：控制栏/歌词的阅读深度
            drawRect(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        palette.dark.blendWith(Color.Black, 0.5f).copy(alpha = 0.88f)
                    ),
                    startY = h * 0.55f
                )
            )
        }

        // 压暗遮罩（歌词可读性）
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha))
        )

        // 极轻噪点（消除色带）
        NoiseOverlay(Modifier.fillMaxSize(), intensity = 0.30f)
    }
}

/** 简单颜色混合 */
private fun Color.blendWith(other: Color, t: Float): Color = Color(
    red = red + (other.red - red) * t,
    green = green + (other.green - green) * t,
    blue = blue + (other.blue - blue) * t,
    alpha = alpha + (other.alpha - alpha) * t
)