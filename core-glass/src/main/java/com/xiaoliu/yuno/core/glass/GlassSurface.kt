package com.xiaoliu.yuno.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xiaoliu.yuno.core.ui.theme.YunoTextPrimary
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

// ======================= Material 3 质感通用组件 =======================
// 规范（需求钦定，全项目遵守）：
//   ✗ 禁止纯色 surface、tonal elevation 变灰、默认 filled card
//   ✓ 半透明白渐变底（0.18 → 0.08）
//   ✓ 1dp 细高光描边（白 0.4 → 0.1 渐变）
//   ✓ 阴影代替颜色层级（阴影只给控件，不当层级语义）
//   ✓ 圆角 24~28dp
//   ✓ 可选极弱噪点
// 真机升级：浮层/交互件传入 hazeState 即切换为真·液态玻璃（采样背景），
// 工艺参数统一在 rememberLiquidGlassStyle（见 LiquidGlass.kt）。

/**
 * 毛玻璃质感容器（静态版）—— 列表骨架、次要卡片用。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    tintTop: Float = 0.22f,
    tintBottom: Float = 0.08f,
    borderTop: Float = 0.10f,
    borderBottom: Float = 0.04f,
    shadowElevation: Dp = 16.dp,
    noise: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = modifier
        .shadow(
            elevation = shadowElevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = 0.45f),
            spotColor = Color.Black.copy(alpha = 0.55f)
        )
        .clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = tintTop),
                    Color.White.copy(alpha = tintBottom)
                )
            )
        )
        .border(
            width = 1.dp,
            brush = Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = borderTop),
                    Color.White.copy(alpha = borderBottom)
                )
            ),
            shape = shape
        )

    Box(glass) {
        if (noise) NoiseOverlay(Modifier.matchParentSize())
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/**
 * 玻璃胶囊标签（chip）。
 * 传 hazeState → 真·液态玻璃（全站 chips 统一升级）；不传 → 静态玻璃兜底。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    hazeState: HazeState? = null
) {
    val shape = RoundedCornerShape(999.dp)

    if (hazeState != null) {
        val style = rememberLiquidGlassStyle(shape = shape, highlight = selected)
        Box(
            modifier = modifier
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = style
                )
                .clip(shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) YunoTextPrimary else YunoTextSecondary
            )
        }
        return
    }

    val bgAlpha = if (selected) 0.30f else 0.10f
    val borderAlpha = if (selected) 0.16f else 0.08f
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = bgAlpha),
                        Color.White.copy(alpha = bgAlpha * 0.6f)
                    )
                )
            )
            .border(1.dp, Color.White.copy(alpha = borderAlpha), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) YunoTextPrimary else YunoTextSecondary
        )
    }
}

/**
 * 圆形玻璃图标按钮 —— 歌词页顶栏的星标/更多、专辑页返回钮。
 * 传 hazeState → 真·液态玻璃；不传 → 静态玻璃兜底。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = Color.White.copy(alpha = 0.92f),
    hazeState: HazeState? = null
) {
    if (hazeState != null) {
        val style = rememberLiquidGlassStyle(shape = CircleShape)
        Box(
            modifier = modifier
                .size(size)
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = style
                )
                .clip(CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(iconSize)
            )
        }
        return
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.06f)
                    )
                )
            )
            .border(
                1.dp,
                Color.White.copy(alpha = 0.10f),
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}
