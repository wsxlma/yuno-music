package com.xiaoliu.yuno.feature.tv

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.component.rememberArtworkPalette
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * TV 流动背景 —— **加强版**（真机口径：电视不用省电，可劲儿动）：
 *   · 两团专辑主色径向光以 18s 周期绕屏漩涡流动，另一团 12s 反向漂移
 *   · 专辑封面大图 90dp 高斯模糊铺底（0.38 透明度，比手机版更浓）
 *   · 上下渐变暗罩兜底，保证前景文字与歌词永远可读
 * 没在播时退回主题紫的慢速流动，屏幕不会死黑。
 */
@Composable
fun TvFlowBackground(track: Track?, modifier: Modifier = Modifier) {
    val palette = rememberArtworkPalette(track?.artworkUrl)
    val flow = rememberInfiniteTransition(label = "tvFlow")
    val angle by flow.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "tvFlowAngle"
    )
    val drift by flow.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "tvFlowDrift"
    )

    Box(modifier.clipToBounds().background(Color(0xFF0A0A10))) {
        // 封面模糊底（电视 GPU 无所谓，加大模糊与浓度）
        if (track != null) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.5f)
                    .blur(90.dp)
                    .alpha(0.38f)
            )
        }

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = max(w, h) * 0.72f
            val a1 = angle / 360f * 2f * PI.toFloat()
            val a2 = a1 + (2f * PI.toFloat() * 0.62f)
            val a3 = drift * 2f * PI.toFloat()

            val c1 = Offset(
                cx + w * 0.46f * cos(a1),
                cy + h * 0.34f * sin(a1 * 1.3f)
            )
            val c2 = Offset(
                cx + w * 0.46f * cos(a2),
                cy + h * 0.34f * sin(a2 * 0.9f)
            )
            val c3 = Offset(
                cx + w * 0.30f * cos(a3),
                cy + h * 0.22f * sin(a3 * 1.7f)
            )

            // 主光团：专辑 vibrant / dominant（无封面时是主题紫系）
            drawRect(
                Brush.radialGradient(
                    colors = listOf(palette.vibrant.copy(alpha = 0.60f), Color.Transparent),
                    center = c1,
                    radius = radius * 1.05f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(palette.dominant.copy(alpha = 0.55f), Color.Transparent),
                    center = c2,
                    radius = radius * 0.85f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(
                        if (track != null) palette.muted.copy(alpha = 0.42f)
                        else YunoAccent.copy(alpha = 0.40f),
                        Color.Transparent
                    ),
                    center = c3,
                    radius = radius * 0.7f
                )
            )

            // 暗罩：上下略深、中部透明，前景可读性兜底
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.36f),
                    0.35f to Color.Transparent,
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.50f)
                )
            )
        }
    }
}