package com.xiaoliu.yuno.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoliu.yuno.core.ui.component.rememberLiquidGlassStyle
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.rememberHazeState

enum class TvTab(val label: String) {
    HOME("首页"),
    LIBRARY("资料库"),
    NOW("正在播放")
}

/**
 * TV 壳：顶栏液态玻璃（首页 / 资料库 / 正在播放）+ 内容区。
 * 顶栏常驻 —— 遥控器永远有路可退，不用猜导航。
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun TvShell(
    vm: TvViewModel,
    modifier: Modifier = Modifier
) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(TvTab.HOME) }
    val hazeState = rememberHazeState()

    Box(modifier.fillMaxSize()) {
        // 内容层 = 玻璃的「背景」（流动背景推满屏，顶栏在其上做玻璃）
        TvFlowBackground(
            track = playback.currentTrack,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(state = hazeState)
        )

        Column(Modifier.fillMaxSize()) {
            TvTopBar(current = tab, onSelect = { tab = it }, hazeState = hazeState)

            Box(Modifier.fillMaxWidth()) {
                when (tab) {
                    TvTab.HOME -> TvHomePage(vm)
                    TvTab.LIBRARY -> TvLibraryPage(vm)
                    TvTab.NOW -> TvNowPlayingPage(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalHazeApi::class)
@Composable
private fun TvTopBar(
    current: TvTab,
    onSelect: (TvTab) -> Unit,
    hazeState: dev.chrisbanes.haze.HazeState
) {
    val barShape = RoundedCornerShape(999.dp)
    val barStyle = rememberLiquidGlassStyle(shape = barShape)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp)
            .hazeGlass(input = HazeInput.Backdrop(hazeState), style = barStyle)
            .clip(barShape)
            .padding(horizontal = 28.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Yuno",
            color = YunoAccent,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "TV",
            color = YunoTextSecondary,
            style = MaterialTheme.typography.labelMedium
        )

        Spacer(Modifier.weight(1f))

        TvTab.entries.forEach { t ->
            val selected = t == current
            Box(
                modifier = Modifier
                    .tvFocusable(onClick = { onSelect(t) }, shape = RoundedCornerShape(999.dp))
                    .background(
                        color = if (selected) Color.White.copy(alpha = 0.14f)
                        else Color.Transparent,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .padding(horizontal = 26.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = t.label,
                    color = if (selected) Color.White else YunoTextSecondary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

/** DI 便捷入口：TvShell 一样走 AppGraph 的 ViewModel 工厂 */
@Composable
fun TvRoot(vm: TvViewModel) {
    TvShell(vm = vm)
}