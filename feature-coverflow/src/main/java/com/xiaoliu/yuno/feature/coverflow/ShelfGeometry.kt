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
    val lift: Float = 0f // 被拎起的高度（px，0 = 贴货架）
)

/** 书脊厚度 = 封面宽度 × 7%（用户钦定基准，真机微调） */
const val BOOK_THICKNESS = 0.07f

/**
 * 货架排布（纯函数，可单测）。centerF = 当前浮点中心（可小数，拖动中连续）。
 *
 * profile（真机 v3 = 真 3D 盒子的逐帧旋转）：
 *  - 横排步距 0.62×书宽；中心两侧各留 0.18×书宽空当（「被推开」的感觉）
 *  - 旋转 = 书的「转开角度」：d=0 停靠角 12°（左下近、右上往里压，不完全正对），
 *    d=1 → 78°，d≥2 → 88°（纯书脊朝前）；右半书架绕左缘右转，左半绕右缘左转
 *  - rotationX：中心书顶缘后仰 8°，远处书立直（0°）
 *  - 尺寸 1/(1+0.32|d|)：邻居 ≈0.76，再外 ≈0.61
 *  - 亮度 1/(1+0.55|d|)
 */
fun computePoses(
    count: Int,
    centerF: Float,
    cx: Float,
    cy: Float,
    baseSize: Float
): List<BookPose> {
    val step = baseSize * 0.62f
    val gapHalf = baseSize * 0.18f
    return (0 until count).map { i ->
        val d = i - centerF
        val a = abs(d)
        val x = cx + d * step +
            when {
                d >= 0.5f -> gapHalf
                d <= -0.5f -> -gapHalf
                else -> 0f
            }
        val size = baseSize / (1f + 0.32f * a)
        val spin = 12f + 66f * a.coerceAtMost(1f) + 10f * (a - 1f).coerceIn(0f, 1f)
        val rotation = if (d >= 0f) spin else -spin
        val tiltX = 8f * (1f - a.coerceAtMost(1f))
        val alpha = 1f / (1f + 0.55f * a)
        val y = cy + (baseSize - size) * 0.18f
        BookPose(x = x, y = y, size = size, rotationY = rotation, rotationX = tiltX, alpha = alpha)
    }
}