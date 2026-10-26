package com.xiaoliu.yuno

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * 播放期间屏幕常亮（真机需求：看歌词/听歌被系统熄屏打断不行）。
 * 挂 FLAG_KEEP_SCREEN_ON 到组合根 View 上；[enabled] 翻回 false 就摘掉，
 * 系统自动熄屏立即恢复。手机/TV 两个专版共用。
 */
@Composable
fun KeepScreenOnInPlayback(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        if (enabled) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}