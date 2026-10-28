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
import kotlin.math.pow

/** 面积加权色块：weight 归一化到 0..1（总和=1），代表该颜色在原封面中的面积占比 */
@Immutable
data class WeightedSwatch(
    val color: Color,
    val weight: Float
)

/**
 * 从专辑封面提取的色板。
 * 用途：歌词页渐变背景（Apple Music 风格）、大标题氛围光、选中态强调色。
 *
 * 需求：「色彩从专辑封面取 vibrant/dark 主色，不要写死 primary」
 * 2026-10-28 追加：[swatches] —— 全部色块按原图面积加权，流动背景按这个比例铺色。
 */
@Immutable
data class ArtworkPalette(
    val dominant: Color,
    val vibrant: Color,
    val dark: Color,
    val light: Color,
    val muted: Color,
    /** 按面积加权的全部色块（面积越大权重越高），供流动背景使用 */
    val swatches: List<WeightedSwatch> = emptyList()
) {
    /** 流动背景用：没有加权色块时回退到旧五个角色色（等权） */
    fun flowingSwatches(): List<WeightedSwatch> =
        swatches.ifEmpty {
            listOf(
                WeightedSwatch(dominant, 0.30f),
                WeightedSwatch(vibrant, 0.25f),
                WeightedSwatch(light, 0.20f),
                WeightedSwatch(muted, 0.15f),
                WeightedSwatch(dark, 0.10f)
            )
        }

    companion object {
        /** 《勇气》封面那种暖棕+蓝灰的兜底色板——无网络/未登录时也不至于难看 */
        val Fallback = ArtworkPalette(
            dominant = Color(0xFF8A6A55),
            vibrant = Color(0xFFC08A5E),
            dark = Color(0xFF16181F),
            light = Color(0xFF9FB4C8),
            muted = Color(0xFF4A4E5A),
            swatches = listOf(
                WeightedSwatch(Color(0xFF8A6A55), 0.34f),
                WeightedSwatch(Color(0xFFC08A5E), 0.24f),
                WeightedSwatch(Color(0xFF9FB4C8), 0.20f),
                WeightedSwatch(Color(0xFF4A4E5A), 0.14f),
                WeightedSwatch(Color(0xFF16181F), 0.08f)
            )
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
    val p = Palette.from(bitmap).maximumColorCount(24).generate()

    // 真机 bug（2026-10-23）：流动背景取到「封面只有一小点的颜色」——
    // 根因是 getVibrantColor/getDarkVibrantColor 只看饱和度不看面积，
    // 小色块颜色 D 哪怕只占封面 2% 也能赢。修法：**按 population（≈面积）排序**，
    // 所有角色只从「面积前 8 名」的色块里挑，小面积色块一律踢出候选。
    val byArea = p.swatches.sortedByDescending { it.population }
    if (byArea.isEmpty()) return@runCatching null
    val top = byArea.take(8)
    val dominantRgb = top.first().rgb                      // 面积最大 = 主色
    val domHsl = top.first().hsl

    // vibrant 位：只在**面积靠前**的颜色里挑「饱和 + 与主色色相差一点」的
    val areaFloor = (top.first().population / 8).coerceAtLeast(1)
    val vibrantRgb = top
        .filter { it.population >= areaFloor }
        .maxByOrNull { s ->
            val dh = Math.abs(s.hsl[0] - domHsl[0]) / 360f
            val hueDist = Math.min(dh, 1f - dh)
            s.hsl[1] * 0.55f + hueDist * 0.30f + s.hsl[2] * 0.15f
        }?.rgb

    // dark / light：面积靠前里最暗 / 最亮
    val darkRgb = top.minByOrNull { it.hsl[2] }?.rgb ?: dominantRgb
    val lightRgb = top.maxByOrNull { it.hsl[2] }?.rgb ?: dominantRgb

    // muted：面积靠前里饱和度最低的；实在没有就拿主色去饱和
    val mutedRgb = top
        .filter { it.hsl[1] < 0.35f }
        .maxByOrNull { it.population }?.rgb
        ?: lerp(Color(dominantRgb), Color.Gray, 0.45f).toArgb()

    // ── 面积加权色块（流动背景用）──
    // 用户钦定（2026-10-28）：每一种颜色都要在背景上有所体现，
    // 面积占比越大背景占比越大；面积权重再乘 1.25 的强调指数（share^1.25 后归一化，
    // 大色块更突出、小色块保留但不抢戏）。
    // 2.6.4 用户第三次反馈：背景里出现"一堆无关杂色" —— 根因是把 population>0 的
    // 全部色块都收了进来（封面边缘的抗锯齿杂色、噪点色全在内）。
    // 改为：只取**面积前 3 大**的颜色，且面积低于第一大色 1/6 的直接丢。
    val topPop = byArea.firstOrNull()?.population ?: 0
    val areaFloor2 = (topPop / 6).coerceAtLeast(1)
    val kept = byArea
        .filter { it.population >= areaFloor2 }
        .take(3)
    val totalPopulation = kept.sumOf { it.population }.coerceAtLeast(1)
    val weighted = kept
        .map { swatch ->
            val share = swatch.population.toFloat() / totalPopulation
            // 2.6.5：提亮上限收到 0.58，避免整块背景发白（用户："取到的颜色有点奇怪"）
            WeightedSwatch(color = Color(swatch.rgb).ensureLuminance(0.05f, 0.58f), weight = share)
        }
        .let { list ->
            // 1.25 强调指数
            val emphasized = list.map { it.copy(weight = it.weight.pow(1.25f)) }
            val sum = emphasized.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1e-6f)
            emphasized.map { it.copy(weight = it.weight / sum) }
        }

    ArtworkPalette(
        dominant = Color(dominantRgb).ensureLuminance(0.16f, 0.8f),
        vibrant = Color(
            vibrantRgb ?: Color(dominantRgb).boostSaturation().toArgb()
        ).ensureLuminance(0.24f),
        dark = Color(darkRgb).ensureLuminance(0.03f, 0.28f),
        light = Color(lightRgb).ensureLuminance(0.34f),
        muted = Color(mutedRgb).ensureLuminance(0.10f, 0.55f),
        swatches = weighted
    )
}.getOrNull()
