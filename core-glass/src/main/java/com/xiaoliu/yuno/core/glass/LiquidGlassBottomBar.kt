package com.xiaoliu.yuno.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.hazeGlass

// ============================================================================
// Liquid Glass 底部导航 —— 双段式：
//   · 左玻璃胶囊：主页 + 资料库（真机反馈：整体加大、向右拉长）
//   · 右圆形玻璃钮：搜索（同步放大；选中只把放大镜染红，不再有高亮底）
// 工艺参数统一走 rememberLiquidGlassStyle（全 App 一套玻璃）。
// ============================================================================

/** 底栏三样（需求钦定：只保留主页 / 资料库 / 搜索） */
enum class BottomTab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "主页", Icons.Rounded.Home),
    LIBRARY("library", "资料库", Icons.Rounded.LibraryMusic),
    SEARCH("search", "搜索", Icons.Rounded.Search)
}

@OptIn(ExperimentalHazeApi::class)
@Composable
fun LiquidGlassBottomBar(
    currentRoute: String?,
    onNavigate: (BottomTab) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val barStyle = rememberLiquidGlassStyle(shape = CircleShape)
    val searchStyle = rememberLiquidGlassStyle(shape = CircleShape)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ———— 左胶囊：主页 + 资料库（加大版：高 68、内容加宽）————
        Row(
            modifier = Modifier
                .height(68.dp)
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = barStyle
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 内胶囊与外胶囊同心：水平边缘 = 3(Spacer) + 3(item padding) = 6dp，
            // 与上下 6dp 边距一致（真机反馈：最左端外壳凸出、内按钮没凸出）
            Spacer(Modifier.width(3.dp))
            BottomTabItem(
                tab = BottomTab.HOME,
                selected = currentRoute == BottomTab.HOME.route
            ) { onNavigate(BottomTab.HOME) }
            BottomTabItem(
                tab = BottomTab.LIBRARY,
                selected = currentRoute == BottomTab.LIBRARY.route
            ) { onNavigate(BottomTab.LIBRARY) }
            Spacer(Modifier.width(3.dp))
        }

        // 弹簧占位：把搜索钮顶在右缘
        Spacer(Modifier.weight(1f))

        // ———— 搜索圆钮：与左胶囊同高（68），保持一致协调；选中只亮红放大镜 ————
        val searchSelected = currentRoute == BottomTab.SEARCH.route
        Box(
            modifier = Modifier
                .size(68.dp)
                .hazeGlass(
                    input = HazeInput.Backdrop(hazeState),
                    style = searchStyle
                )
                .clip(CircleShape)
                .clickable { onNavigate(BottomTab.SEARCH) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = BottomTab.SEARCH.icon,
                contentDescription = BottomTab.SEARCH.label,
                tint = if (searchSelected) YunoAccent else Color.White.copy(alpha = 0.90f),
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * 胶囊里的 Tab 项。
 * 选中态 = 与外层同心（高 56 / 半径 28，外层 68 / 半径 34，均匀 6dp 边距），
 * 两圈弧度平行不打架。
 */
@Composable
private fun BottomTabItem(
    tab: BottomTab,
    selected: Boolean,
    onClick: () -> Unit
) {
    val capsule = CircleShape
    val contentColor = if (selected) YunoAccent else Color.White.copy(alpha = 0.88f)

    Column(
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .height(56.dp)
            .clip(capsule)
            .clickable(onClick = onClick)
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}
