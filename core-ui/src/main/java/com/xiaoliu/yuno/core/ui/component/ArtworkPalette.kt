package com.xiaoliu.yuno.core.ui.component

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从专辑封面提取的色板。
 * 用途：歌词页渐变背景（Apple Music 风格）、大标题氛围光、选中态强调色。
 *
 * 需求：「色彩从专辑封面取 vibrant/dark 主色，不要写死 primary」
 */
@Immutable
data class ArtworkPalette(
    val dominant: Color,
    val vibrant: Color,
    val dark: Color,
    val light: Color,
    val muted: Color
) {
    /** 歌词页底色渐变用的颜色序列（暗 → 主 → 彩 → 亮） */
    fun gradientColors(): List<Color> = listOf(dark, dominant, vibrant, light)

    companion object {
        /** 《勇气》封面那种暖棕+蓝灰的兜底色板——无网络/未登录时也不至于难看 */
        val Fallback = ArtworkPalette(
            dominant = Color(0xFF8A6A55),
            vibrant = Color(0xFFC08A5E),
            dark = Color(0xFF16181F),
            light = Color(0xFF9FB4C8),
            muted = Color(0xFF4A4E5A)
        )
    }
}

/** 把亮度抬进 [min, max] 区间，太暗的封面色直接用于背景会一片死黑 */
private fun Color.ensureLuminance(min: Float, max: Float = 1f): Color {
    val l = luminance()
    return when {
        l < min -> lerp(this, Color.White, (((min - l) / (1f - l)).coerceIn(0f, 1f)) * 0.65f)
        l > max -> lerp(this, Color.Black, ((l - max) / l).coerceIn(0f, 1f))
        else -> this
    }
}

/**
 * 提高饱和度/明度（进度条配色专用 —— "颜色更饱和、对比度更高，整体更显眼"）。
 */
fun Color.boostSaturation(saturationFactor: Float = 1.35f, valueFactor: Float = 1.10f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valueFactor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = this.alpha)
}

/**
 * 拿到封面图 → Palette 提取 → 返回可用的色板。
 * 结果随 artworkUrl 变化缓存，同一张封面只算一次（重组不重算）。
 */
@Composable
fun rememberArtworkPalette(artworkUrl: String?): ArtworkPalette {
    val appContext = LocalContext.current.applicationContext
    // Coil 3：全局单例 ImageLoader（coil-network-okhttp 在依赖里，网络 fetcher 自动注册）
    val loader = remember { SingletonImageLoader.get(appContext) }
    var palette by remember(artworkUrl) { mutableStateOf(ArtworkPalette.Fallback) }

    LaunchedEffect(artworkUrl) {
        if (artworkUrl.isNullOrBlank()) {
            palette = ArtworkPalette.Fallback
            return@LaunchedEffect
        }
        palette = withContext(Dispatchers.IO) {
            extractPalette(appContext, loader, artworkUrl)
        } ?: ArtworkPalette.Fallback
    }
    return palette
}

private suspend fun extractPalette(
    context: Context,
    loader: ImageLoader,
    url: String
): ArtworkPalette? = runCatching {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(160)              // 分析用缩略图就够，别拉全尺寸
        .allowHardware(false)   // Palette 需要读像素
        .build()
    val result = loader.execute(request) as? SuccessResult
        ?: return@runCatching null
    val bitmap = result.image.toBitmap()
    val fb = ArtworkPalette.Fallback
    val p = Palette.from(bitmap).clearFilters().maximumColorCount(32).generate()

    ArtworkPalette(
        dominant = Color(p.getDominantColor(fb.dominant.toArgb())).ensureLuminance(0.16f, 0.8f),
        vibrant = Color(
            p.getVibrantColor(p.getMutedColor(fb.vibrant.toArgb()))
        ).ensureLuminance(0.24f),
        dark = Color(
            p.getDarkVibrantColor(
                p.getDarkMutedColor(fb.dark.toArgb())
            )
        ).ensureLuminance(0.03f, 0.28f),
        light = Color(
            p.getLightVibrantColor(p.getLightMutedColor(fb.light.toArgb()))
        ).ensureLuminance(0.34f),
        muted = Color(p.getMutedColor(fb.muted.toArgb())).ensureLuminance(0.10f, 0.55f)
    )
}.getOrNull()
