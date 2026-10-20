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
// 真机反馈第六轮（2026-10-19）——「一步到位」极限通透版：
// 用户反复反馈"不够透"。这次把所有光学量一次推到极限，不留余地：
//   · 模糊 32dp → 8dp（几乎不糊，背景清清楚楚）
//   · 折射拉满：strength 1.0 / 位移 32dp（玻璃边缘内容明显扭曲变形）
//   · 色散拉满：chromaticAberration 1.0（边缘实时彩边）
//   · 底色几乎全透：alpha 0.17 → 0.06、白色 tint 0.10 → 0.05
//   · 高光 0.90（白描边清晰）、环境响应 0.50
//   · GlassOptics 在 2.0.1 是 data class（没有 .regular）——直接构造
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
