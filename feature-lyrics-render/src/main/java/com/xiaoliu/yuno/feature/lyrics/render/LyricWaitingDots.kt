package com.xiaoliu.yuno.feature.lyrics.render

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * 单个等待点：**从暗到亮渐亮一次**，亮了就保持。
 *
 * ⚠️ 真机 Beta13 根治"三个点常亮"：
 *    旧法用 `animateFloatAsState(targetValue = 1f)` ——
 *    该 API **只在目标值变化时才动画**，首次组合直接就是 1f，
 *    所以三个点一上来就全亮，从来没渐亮过。
 *    改用 `Animatable` + `LaunchedEffect`：进组合时从 0 显式起播。
 */
@Composable
private fun WaitingDot(index: Int) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.snapTo(0f)
        // 0ms / 110ms / 220ms 依次起播，各 300ms 渐亮
        anim.animateTo(1f, tween(durationMillis = 300, delayMillis = index * 110))
    }
    Box(
        Modifier
            .size(13.dp)
            .graphicsLayer { this.alpha = anim.value.coerceIn(0f, 1f) }
            .background(Color.White, CircleShape)
    )
}

/**
 * 「歌词等待」动画 —— 前奏 / 间奏（歌词空档）时显示，占住一行。
 *
 * 规格：3 个点**从左到右逐个渐亮**，亮了就保持不灭（不做呼吸循环）。
 *
 * ## 为什么重写（真机 Beta12）
 *
 * 十几个版本这球一直"隐身"，根因是**它自己那套手写计时器太脆**：
 * `LaunchedEffect` 里 `delay(50)` 循环推进 `tMs`，只要组件被重组/复用一次，
 * 动画就从头再来或停在 0 —— 于是永远是全透明。
 *
 * 现在改成 **`animateFloatAsState` + `delayMillis` 错峰**：
 *  * 由 Compose 动画系统驱动，不手写时钟
 *  * 每个点自带延迟（0 / 110 / 220ms）→ 天然"从左到右依次亮"
 *  * 组件进入组合就自动播一次，退场再进来再播一次
 *
 * 另外加了一层**半透明胶囊底**，保证在任何专辑配色背景上都看得见。
 */
@Composable
fun LyricWaitingDots(modifier: Modifier = Modifier) {
    // 行高与歌词行对齐（LyricLineSpacing×2 ≈ 32dp 之上再留一行高），保证第一句落位精确
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            repeat(3) { i ->
                WaitingDot(index = i)
            }
        }
    }
}
