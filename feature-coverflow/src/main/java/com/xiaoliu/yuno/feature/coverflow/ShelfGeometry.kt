package com.xiaoliu.yuno.feature.coverflow

import kotlin.math.abs

/** 一本书在货架上的姿态（纯数据，渲染与手势共用） */
data class BookPose(
    val x: Float,        // 书中心 x（px）
    val y: Float,        // 书中心 y（px）
    val size: Float,     // 封面边长（px）
    val rotationY: Float,// 度；正 = 绕左缘右转（右侧远去）；负 = 绕右缘左转
    val rotationX: Float = 0f, // 度；正 = 顶缘后仰（真机：中心书右上往里压）
    val alpha: Float,    // 亮度
    val lift: Float = 0f, // 被拎起的高度（px，0 = 贴货架）
    /** true = 绕左缘旋转（右半书架/中央）；false = 绕右缘旋转（左半书架） */
    val pivotLeft: Boolean = true
)

/** 书脊厚度 = 封面宽度 × 7%（用户钦定基准，真机微调） */
const val BOOK_THICKNESS = 0.07f

/**
 * 货架排布（纯函数，可单测）。centerF = 当前浮点中心（可小数，拖动中连续）。
 *
 * profile（对照参考截图逐项校准，v4）：
 *  - 横排步距 0.62×书宽，**没有中心空当**——邻居是紧挨着的（真机：不要推开）
 *  - 中央书停靠角 18°：右缘向后收（右后方远），左下近、右缘往里压
 *  - 左右邻居都是「扇开」：**外缘朝观众翘、面向里收**，书脊包边在各自外侧；
 *    等效旋转 = 负角，右半绕内(左)缘、左半绕内(右)缘 —— 离中心越远越接近
 *    纯书脊（|d|=1 → -78°，|d|≥2 → -88°）
 *  - rotationX：中心书顶缘后仰 4°，远处立直
 *  - 尺寸 1/(1+0.32|d|)：邻居 ≈0.76，再外 ≈0.61；亮度 1/(1+0.55|d|)
 */
fun computePoses(
    count: Int,
    centerF: Float,
    cx: Float,
    cy: Float,
    baseSize: Float
): List<BookPose> {
    val step = baseSize * 0.62f
    return (0 until count).map { i ->
        val d = i - centerF
        val a = abs(d)
        val x = cx + d * step
        val size = baseSize / (1f + 0.32f * a)
        // 中央 +18°；随 |d| 线性摆向 -88°（左右统一一条曲线，过中心不跳变）
        val beyond = 28f * (a - 1f).coerceIn(0f, 1f)
        val rotation = 18f - 78f * a.coerceAtMost(1f) - beyond
        val tiltX = 4f * (1f - a.coerceAtMost(1f))
        val alpha = 1f / (1f + 0.55f * a)
        val y = cy + (baseSize - size) * 0.18f
        BookPose(
            x = x, y = y, size = size,
            rotationY = rotation, rotationX = tiltX,
            alpha = alpha,
            pivotLeft = d >= 0f
        )
    }
}