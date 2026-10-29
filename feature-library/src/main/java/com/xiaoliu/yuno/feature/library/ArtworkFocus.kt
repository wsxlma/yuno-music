package com.xiaoliu.yuno.feature.library

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一张封面的「形状」信息：宽高比 + 内容重心 */
internal data class ArtworkMeta(
    /** 原图 宽 / 高。1.0 = 正方形 */
    val aspect: Float,
    /** 裁剪对齐点（按内容重心算） */
    val alignment: Alignment
)

/** 算不出来时的默认：按正方形处理，焦点偏左上（封面标题/logo 大多在那） */
private val DefaultMeta = ArtworkMeta(1f, BiasAlignment(-0.30f, -0.32f))

/**
 * 封面元信息（2026-10-29 2.9.0）。
 *
 * 为什么要 aspect：用户报「常听」被裁掉 —— 那张封面是**横图**，卡片是竖的。
 * 横图塞进竖卡，Crop 必然砍掉左右，角上的字必死。**调对齐点救不回来**：
 * 对齐点只决定砍哪边，不决定砍多少。所以横图必须走「完整显示 + 模糊延伸」，不能裁。
 *
 * 重心仍然算（竖图 / 方图用）：缩略图 → 平均色 → 差异加权重心 → BiasAlignment。
 */
internal object ArtworkMetaCache {

    private val cache = LruCache<String, ArtworkMeta>(256)

    fun cached(url: String): ArtworkMeta? = cache.get(url)

    fun store(url: String, meta: ArtworkMeta) {
        cache.put(url, meta)
    }

    suspend fun compute(context: android.content.Context, url: String): ArtworkMeta? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(48)
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap: Bitmap =
                    (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null

                val w = bitmap.width
                val h = bitmap.height
                if (w < 4 || h < 4) return@runCatching null
                val aspect = w.toFloat() / h.toFloat()

                val px = IntArray(w * h)
                bitmap.getPixels(px, 0, w, 0, 0, w, h)

                var sr = 0L
                var sg = 0L
                var sb = 0L
                for (c in px) {
                    sr += (c shr 16) and 0xFF
                    sg += (c shr 8) and 0xFF
                    sb += c and 0xFF
                }
                val n = px.size.toLong()
                val ar = (sr / n).toInt()
                val ag = (sg / n).toInt()
                val ab = (sb / n).toInt()

                var sw = 0.0
                var sx = 0.0
                var sy = 0.0
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0 until w) {
                        val c = px[row + x]
                        val d = abs(((c shr 16) and 0xFF) - ar) +
                            abs(((c shr 8) and 0xFF) - ag) +
                            abs((c and 0xFF) - ab)
                        // 阈值 24：角上的小字面积很小，阈值一高就被当背景滤掉
                        if (d > 24) {
                            val dw = d.toDouble()
                            sw += dw
                            sx += dw * x
                            sy += dw * y
                        }
                    }
                }
                if (sw < 1.0) return@runCatching ArtworkMeta(aspect, DefaultMeta.alignment)

                val cx = (sx / sw / w).toFloat()
                val cy = (sy / sw / h).toFloat()
                val bx = ((cx * 2f - 1f) * 0.8f).coerceIn(-1f, 1f)
                val by = ((cy * 2f - 1f) * 0.8f).coerceIn(-1f, 1f)
                ArtworkMeta(aspect, BiasAlignment(bx, by))
            }.getOrNull()
        }
}

@Composable
internal fun rememberArtworkMeta(url: String?): ArtworkMeta {
    val context = LocalContext.current
    var meta by remember(url) {
        mutableStateOf(url?.let { ArtworkMetaCache.cached(it) } ?: DefaultMeta)
    }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) return@LaunchedEffect
        if (ArtworkMetaCache.cached(url) != null) return@LaunchedEffect
        val m = ArtworkMetaCache.compute(context, url) ?: DefaultMeta
        ArtworkMetaCache.store(url, m)
        meta = m
    }
    return meta
}

/**
 * 给内容罩一层边缘渐隐（`DstIn` 遮罩）。
 *
 * 为什么要单独包一层 Box：直接把 `graphicsLayer + drawWithContent` 挂在 AsyncImage 上时，
 * AsyncImage 内部自己也会建图层（淡入/缩放），遮罩容易作用在错误的节点上 —— 表现就是
 * "渐隐根本没生效"。包在外层 Box、`compositingStrategy = Offscreen` 之后，
 * 遮罩作用的是整块已经画完的内容，稳定生效。
 *
 * @param horizontal true = 左右渐隐；false = 上下渐隐
 * @param fade 渐隐带占整条边的比例（0.20 = 左右各 20%）
 */
@Composable
private fun EdgeFaded(
    horizontal: Boolean,
    fade: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val f = fade.coerceIn(0f, 0.45f)
                val brush = if (horizontal) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        f to Color.Black,
                        1f - f to Color.Black,
                        1f to Color.Transparent
                    )
                } else {
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        f to Color.Black,
                        1f - f to Color.Black,
                        1f to Color.Transparent
                    )
                }
                drawRect(brush = brush, blendMode = BlendMode.DstIn)
            }
    ) {
        content()
    }
}

/**
 * 完整显示的原图 + 按**图自身**尺寸做的边缘渐隐。
 *
 * 先按 `ContentScale.Fit` 的规则算出图片在卡里实际占的矩形（居中），
 * 再把 [EdgeFaded] 的尺寸设成这个矩形 —— 遮罩的百分比这才对得上图片边缘，
 * 渐隐带才会真的压在图的两侧/上下。
 */
@Composable
private fun FitCoverFaded(
    url: String?,
    contentDescription: String?,
    aspect: Float,
    horizontal: Boolean,
    fade: Float
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val cardW = constraints.maxWidth.toFloat()
        val cardH = constraints.maxHeight.toFloat()
        if (cardW <= 0f || cardH <= 0f) return@BoxWithConstraints

        val a = aspect.coerceIn(0.30f, 3.20f)
        val fitW: Float
        val fitH: Float
        if (cardW / cardH > a) {
            fitH = cardH
            fitW = cardH * a
        } else {
            fitW = cardW
            fitH = cardW / a
        }

        EdgeFaded(
            horizontal = horizontal,
            fade = fade,
            modifier = Modifier
                .width(with(density) { fitW.toDp() })
                .height(with(density) { fitH.toDp() })
        ) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 统一的封面绘制（主墙 & 三排墙都用它）。
 *
 * ★ 2026-10-29 2.9.1 修正：上一版只按**图片**比例判断（横图就走上下模糊），
 *   没考虑**卡片**比例 —— 结果一张竖长图放进三排墙的宽卡里，被当成"横图"走了
 *   Crop，直接裁掉了两侧（用户报："宽图咋变成裁切了"）。
 *
 * 现在按 **图片比例 ÷ 卡片比例** 判断，一张逻辑覆盖全部情况：
 *
 *   ratio≈1（图与卡形状接近）  → Crop 铺满（裁剪量 < 12%，看不出来）
 *   ratio < 1（图比卡窄，比如竖长图放进宽卡）→ **原图完整 + 左右毛玻璃**（+ 轻微左右渐隐过渡）
 *   ratio > 1（图比卡宽，比如横图放进竖卡）→ **原图完整 + 上下毛玻璃**
 *
 * 毛玻璃永远是「同一张图铺满 + 强模糊 + 压暗」，不是纯色 —— 纯色填会显脏。
 *
 * @param cardAspect 卡片的 宽/高。调用方本来就算好了（widthPx/heightPx），
 *                   所以不需要 BoxWithConstraints 去测量，零成本。
 */
@Composable
internal fun CoverImage(
    url: String?,
    contentDescription: String?,
    cardAspect: Float,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 24.dp,
    scrim: Float = 0.40f
) {
    val meta = rememberArtworkMeta(url)
    val ratio = if (cardAspect > 0.01f) meta.aspect / cardAspect else 1f

    Box(modifier.clipToBounds()) {
        when {
            // 形状接近：直接铺满（这点裁剪量肉眼看不出来）
            ratio in 0.85f..1.20f -> {
                AsyncImage(
                    model = url,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    alignment = meta.alignment,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 图比卡窄（竖长图 / 方图放进宽卡）：原图完整，左右用毛玻璃铺满
            ratio < 0.85f -> {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(blurRadius)
                )
                // 横向渐变压暗：两侧更深，给"往中间收"的方向感
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0f to Color.Black.copy(alpha = (scrim + 0.18f).coerceAtMost(1f)),
                                0.5f to Color.Black.copy(alpha = scrim),
                                1f to Color.Black.copy(alpha = (scrim + 0.18f).coerceAtMost(1f))
                            )
                        )
                )
                // 原图：完整显示 + 左右各 18% 渐隐融进毛玻璃。
                // ★ 遮罩必须锚在「图自己的矩形」上，不能锚在整张卡上 ——
                //   2.9.0~2.9.2 就是这么错的，渐隐带全落在图外面的模糊层里，等于没做。
                //   0.18 是 2.8.0b11 的原始值（用户："照着写回去"）。
                FitCoverFaded(
                    url = url,
                    contentDescription = contentDescription,
                    aspect = meta.aspect,
                    horizontal = true,
                    fade = 0.18f
                )
            }

            // 图比卡宽（横图放进竖卡）：原图完整，上下用毛玻璃铺满
            else -> {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(blurRadius)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = (scrim + 0.14f).coerceAtMost(1f)),
                                0.5f to Color.Black.copy(alpha = scrim),
                                1f to Color.Black.copy(alpha = (scrim + 0.14f).coerceAtMost(1f))
                            )
                        )
                )
                // 同上：锚在图矩形，上下各 18%（之前 0.08 窄到看不出来）
                FitCoverFaded(
                    url = url,
                    contentDescription = contentDescription,
                    aspect = meta.aspect,
                    horizontal = false,
                    fade = 0.18f
                )
            }
        }
    }
}
