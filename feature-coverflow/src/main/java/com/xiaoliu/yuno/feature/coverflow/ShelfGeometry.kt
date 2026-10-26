package com.xiaoliu.yuno.feature.coverflow

import kotlin.math.abs

/** 一本书在货架上的姿态（纯数据，渲染与手势共用） */
data class BookPose(
    val x: Float,        // 书中心 x（px）
    val y: Float,        // 书中心 y（px）
    val size: Float,     // 封面边长（px）
    val rotationY: Float,// 度；0 = 正对观众；± = 左右转向
    val alpha: Float,    // 亮度
    val lift: Float = 0f // 被拎起的高度（px，0 = 贴货架）
)

/** 书脊厚度 = 封面宽度 × 7%（用户钦定基准，真机微调） */
const val BOOK_THICKNESS = 0.07f

/**
 * 货架排布（纯函数，可单测）。centerF = 当前浮点中心（可小数，拖动中连续）。
 *
 * profile 按参考视频/截图校准：
 *  - 横排步距 0.62×书宽；中心两侧各留 0.16×书宽的空当（「被推开」的感觉）
 *  - 转角随 |d| 收敛到 88°（d=1 → 51.8°，d=2 → 65.2°，d=3 → 71.4°，
 *    离中心越远越接近「书脊朝前」）
 *  - 尺寸 1/(1+0.32|d|)：邻居 ≈0.76，再外 ≈0.61
 *  - 亮度 1/(1+0.55|d|)；中心书最低最近（近大远小），远处略上浮
 */
fun computePoses(
    count: Int,
    centerF: Float,
    cx: Float,
    cy: Float,
    baseSize: Float
): List<BookPose> {
    val step = baseSize * 0.62f
    val gapHalf = baseSize * 0.16f
    return (0 until count).map { i ->
        val d = i - centerF
        val x = cx + d * step +
            when {
                d >= 0.5f -> gapHalf
                d <= -0.5f -> -gapHalf
                else -> 0f
            }
        val size = baseSize / (1f + 0.32f * abs(d))
        val rotation = if (d >= 0f) 88f * d / (d + 0.7f) else 88f * d / (d - 0.7f)
        val alpha = 1f / (1f + 0.55f * abs(d))
        val y = cy + (baseSize - size) * 0.18f
        BookPose(x = x, y = y, size = size, rotationY = rotation, alpha = alpha)
    }
}