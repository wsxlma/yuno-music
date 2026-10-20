package com.xiaoliu.yuno.core.ui.component

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlin.random.Random

/**
 * 极弱噪点纹理 —— 「质感提升手段」里的一味调料。
 *
 * 生成一张 96x96 的随机灰度小图，用 BitmapShader REPEAT 平铺。
 * 固定随机种子：每次进 App 的噪点一致，避免重组时闪来闪去。
 */
private class NoiseShaderBrush(private val bitmap: Bitmap) : ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): Shader =
        BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}

@Composable
fun rememberNoiseBrush(maxAlpha: Int = 10, tile: Int = 96, seed: Int = 42): Brush {
    return remember(maxAlpha, tile, seed) {
        val rnd = Random(seed)
        val pixels = IntArray(tile * tile) {
            val v = rnd.nextInt(256)
            val a = rnd.nextInt(maxAlpha + 1)
            (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        val bmp = Bitmap.createBitmap(pixels, tile, tile, Bitmap.Config.ARGB_8888)
        NoiseShaderBrush(bmp)
    }
}

/** 铺一层极弱噪点。intensity 控制整体透明度（默认很克制） */
@Composable
fun NoiseOverlay(
    modifier: Modifier = Modifier,
    intensity: Float = 0.6f
) {
    val brush = rememberNoiseBrush()
    Canvas(modifier = modifier.alpha(intensity)) {
        drawRect(brush = brush)
    }
}
