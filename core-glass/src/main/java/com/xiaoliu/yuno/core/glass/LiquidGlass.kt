package com.xiaoliu.yuno.core.ui.component

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.glass.GlassOptics
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue

// ============================================================================
// 全局统一的液态玻璃工艺 —— 全 App 所有玻璃面都从这里取参数。
//
// ★ 2026-10-29 2.8.0b5：这里**回到 b3 那一版**（用户认可的状态）。
//   上一轮我把参数改成 0.20 / 0.10 / 0.65 / 0.28 并全局换输入通道，结果底栏胶囊
//   和搜索按钮出现很粗的白描边 —— 白边是 specularIntensity 0.65 + 32dp 折射
//   叠加出来的，不是"浓度不够"的解法。所以：大面板一律用下面这套原始值。
//
// ★ 小球（圆形图标按钮）走 rememberLiquidGlassBallStyle —— 见下面说明。
// ============================================================================

@OptIn(ExperimentalHazeApi::class)
@Composable
fun rememberLiquidGlassStyle(
    shape: RoundedCornerShape = CircleShape,
    highlight: Boolean = false,
    surfaceAlpha: Float? = null
): GlassStyle = remember(shape, highlight, surfaceAlpha) {
    GlassStyle.regular.then {
        backgroundColor(
            Color(0xFF0B0B12).copy(
                alpha = surfaceAlpha ?: if (highlight) 0.10f else 0.06f
            )
        )
        tint(Color.White.copy(alpha = if (highlight) 0.10f else 0.05f))
        optics(
            GlassOptics(
                refractionStrength = 1.0f,
                refractionDisplacement = 32.dp,
                blurRadius = OpticalSizeValue.Fixed(8.dp),
                refractionDetailIntensity = 0.6f
            )
        )
        chromaticAberrationStrength(1.0f)
        // 白描边太抢戏（真机反馈：搜索钮等周边一圈白边很丑）——镜面高光 0.90 → 0.45
        specularIntensity(0.45f)
        ambientResponse(0.50f)
        shape(shape)
    }
}

/**
 * 44dp 上下的**圆形玻璃球**专用工艺（2026-10-29 2.8.0b5）。
 *
 * 为什么不能直接复用 rememberLiquidGlassStyle：
 * 那套的折射位移是 **32dp**，是按整条底栏那个尺寸调的。压到一个 44dp 的圆上，
 * 32dp 的位移等于把整个球的边缘都卷进去 → 视觉上就是**一圈很粗的白/灰环**，
 * 里面反而空。用户看到的"退出按钮不是玻璃球"就是这个原因：要么糊成黑球
 * （浓度 0.06），要么粗白边（浓度/高光拉满），两种都不是玻璃。
 *
 * 这里按球径重新配比：
 *   · 折射位移 6dp（只卷边缘一点点，球心保持清楚）
 *   · 模糊 14dp（小球需要更强的磨砂，才有"实体玻璃"的体量）
 *   · 底色 0.16 + 白 tint 0.12（有体积，能压住亮封面）
 *   · 高光 0.55（边缘一道细亮边，不是粗白框）
 *   · 色散 0.25（小球上彩边一强就脏）
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun rememberLiquidGlassBallStyle(
    highlight: Boolean = false,
    surfaceAlpha: Float? = null
): GlassStyle = remember(highlight, surfaceAlpha) {
    GlassStyle.regular.then {
        backgroundColor(
            Color(0xFF0B0B12).copy(
                alpha = surfaceAlpha ?: if (highlight) 0.10f else 0.07f
            )
        )
        tint(Color.White.copy(alpha = if (highlight) 0.09f else 0.06f))
        optics(
            GlassOptics(
                refractionStrength = 0.55f,
                refractionDisplacement = 6.dp,
                blurRadius = OpticalSizeValue.Fixed(14.dp),
                refractionDetailIntensity = 0.35f
            )
        )
        chromaticAberrationStrength(0.22f)
        specularIntensity(0.42f)
        ambientResponse(0.26f)
        shape(CircleShape)
    }
}
