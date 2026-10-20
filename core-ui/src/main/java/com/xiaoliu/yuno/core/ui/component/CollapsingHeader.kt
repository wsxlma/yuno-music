package com.xiaoliu.yuno.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

/**
 * M3 LargeTopAppBar 式折叠大标题 —— Material 3 的招牌滚动交互：
 * 列表滚动前 ~300px 内，标题从 34sp 收到 21sp、上下留白同步收窄。
 *
 * @param collapseFractionProvider 0 = 完全展开，1 = 完全收回（调用方按列表滚动位置算）
 * @param subtitle 副标题（拿到 fraction 自行淡出/收拢）
 */
@Composable
fun CollapsingHeader(
    title: String,
    collapseFractionProvider: () -> Float,
    modifier: Modifier = Modifier,
    subtitle: (@Composable (collapseFraction: Float) -> Unit)? = null
) {
    val fraction = collapseFractionProvider().coerceIn(0f, 1f)
    Column(
        modifier = modifier.padding(
            // 真机反馈：一级页内容整体上移 ~60-70px —— 顶部留白收窄
            top = lerp(8.dp, 4.dp, fraction),
            bottom = lerp(4.dp, 2.dp, fraction)
        )
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = lerp(30.sp, 21.sp, fraction),
            lineHeight = lerp(36.sp, 26.sp, fraction),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.2.sp,
            maxLines = 1
        )
        subtitle?.invoke(fraction)
    }
}
