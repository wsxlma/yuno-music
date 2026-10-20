package com.xiaoliu.yuno.core.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

// ============================================================================
// 主题 —— Material 3 骨架 + Apple Music 身份色：
//   · Android 12+ 接入 **Material You 动态取色**（桌面壁纸色系进主题），
//     primary 保留品牌红（客户端身份）；拿不到动态色时退回精调暗色方案
//   · 深色优先：第三方 Apple Music 客户端与 Apple 官方一致（深色为基调），
//     系统深色模式下完全一致
// ============================================================================

/** 静态暗色兜底（API < 31 或动态取色不可用） */
private val YunoDarkScheme = darkColorScheme(
    primary = YunoAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1B22),
    onPrimaryContainer = YunoAccentSoft,
    secondary = YunoAccentSoft,
    onSecondary = Color.White,
    background = YunoInk,
    onBackground = YunoTextPrimary,
    surface = YunoInkSoft,
    onSurface = YunoTextPrimary,
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = YunoTextSecondary,
    surfaceContainer = Color(0xFF16161E),
    surfaceContainerHigh = Color(0xFF1C1C26),
    outline = Color(0x33FFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFFF6B6B),
    onError = Color.White
)

@Composable
fun YunoTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(context) {
        val dynamic = if (Build.VERSION.SDK_INT >= 31) {
            runCatching { dynamicDarkColorScheme(context) }.getOrNull()
        } else {
            null
        }
        if (dynamic != null) {
            // Material You 动态底 + 品牌红强调（Apple Music 身份不丢）
            dynamic.copy(
                primary = YunoAccent,
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A1B22),
                onPrimaryContainer = YunoAccentSoft,
                secondary = YunoAccentSoft,
                onSecondary = Color.White,
                error = Color(0xFFFF6B6B),
                onError = Color.White
            )
        } else {
            YunoDarkScheme
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = YunoTypography,
        shapes = YunoShapes,
        content = content
    )
}

/**
 * 全局氛围底 —— Material You 在暗色下的可视窗口：
 * 顶部一层 tertiary（壁纸色系的深调）微染 → 中段过渡 → 底色收底。
 * 壁纸换色，背景氛围跟着变；玻璃组件采样它也有层次可折射。
 */
@Composable
fun rememberYunoAmbientBrush(): Brush {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.background, scheme.surface, scheme.tertiary, scheme.primary) {
        val top = lerp(scheme.surface, scheme.tertiary, 0.22f)
        val mid = lerp(scheme.background, scheme.primary, 0.05f)
        Brush.verticalGradient(
            0f to top,
            0.45f to mid,
            1f to scheme.background
        )
    }
}
