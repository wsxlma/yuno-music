package com.xiaoliu.yuno.feature.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary

// ============================================================================
// 遥控器适配：方向键 = 走焦点；OK = 播放（单击）；长按 OK 或菜单键 = 呼出
// 「加入待播列表 / 插播」菜单。语义与手机端 TrackRow 的长按菜单一一对应。
// ============================================================================

/**
 * TV 焦点卡片的通用修饰：
 *   · 焦点可视化 = 主题色描边 + 1.04 微放大（双方向键走焦点自明）
 *   · OK 键 → [onClick]（播放）；长按 OK → [onLongClick]（菜单）
 *   · 菜单键（Key.Menu / Key.Settings，覆盖不同遥控器的键值）→ [onMenu]
 */
@Composable
fun Modifier.tvFocusable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onMenu: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(18.dp),
    focusRequester: FocusRequester? = null
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(140),
        label = "tvFocusScale"
    )
    val highlight = Modifier.border(
        width = if (focused) 3.dp else 0.dp,
        color = if (focused) YunoAccent.copy(alpha = 0.9f) else Color.Transparent,
        shape = shape
    )
    val focusReq = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    return this
        .then(highlight)
        .then(focusReq)
        .combinedClickable(
            onClick = onClick,
            onLongClick = { onLongClick?.invoke() }
        )
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown &&
                (event.key == Key.Menu || event.key == Key.Settings)
            ) {
                onMenu?.invoke()
                true
            } else false
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
}

/**
 * TV 曲目菜单（居中玻璃面板）：菜单键/长按 OK 呼出。
 * 方向键在两个动作间走焦点，OK 执行，返回键/取消关闭。
 */
@Composable
fun TvTrackMenu(
    track: Track?,
    onDismiss: () -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlayNext: (Track) -> Unit
) {
    if (track == null) return
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(track) { firstFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF191920).copy(alpha = 0.97f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
            modifier = Modifier.width(440.dp)
        ) {
            Column(
                Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = track.title,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = track.artist,
                    color = YunoTextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(24.dp))
                TvMenuRow("加入待播列表", firstFocus) { onAddToQueue(track); onDismiss() }
                Spacer(Modifier.height(10.dp))
                TvMenuRow("插播") { onPlayNext(track); onDismiss() }
                Spacer(Modifier.height(10.dp))
                TvMenuRow("取消") { onDismiss() }
            }
        }
    }
}

@Composable
private fun TvMenuRow(
    text: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(
                onClick = onClick,
                shape = RoundedCornerShape(14.dp),
                focusRequester = focusRequester
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}