package com.xiaoliu.yuno

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import com.xiaoliu.yuno.core.ui.theme.YunoInk
import com.xiaoliu.yuno.core.ui.theme.YunoTheme
import com.xiaoliu.yuno.nav.AppNavHost
import com.xiaoliu.yuno.ui.LoginScreen
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode

/** 手机专版入口（TV 专版的 MainActivity 在 tv 源码集里，互不掺杂） */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as YunoApp).graph

        setContent {
            // 全局 Haze 采样切到省电档（真机反馈：5.8W 耗电 ≈ 原神）。
            // 玻璃渲染用一半分辨率采样（Performance 档），观感几乎无损、渲染成本大降。
            CompositionLocalProvider(
                LocalHazePerformanceMode provides HazePerformanceMode.Performance
            ) {
                YunoTheme {
                    // 播放期间保持屏幕常亮：歌在放，歌词界面就不能被系统熄屏抢走。
                    val playback by PlaybackStateHub.state.collectAsStateWithLifecycle()
                    KeepScreenOnInPlayback(playback.isPlaying)

                    val primed by graph.authPrimed.collectAsStateWithLifecycle()
                    val loggedIn by graph.authStore.isLoggedIn
                        .collectAsStateWithLifecycle(initialValue = false)
                    // 通知点击跳转：extra "open_lyrics"=true → 启动后直接进歌词页
                    val autoOpenPlayer = intent.getBooleanExtra("open_lyrics", false)

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(YunoInk)
                    ) {
                        when {
                            !primed -> Unit                       // 凭据读取（毫秒级），黑底兜着
                            !loggedIn -> LoginScreen(graph = graph)
                            else -> AppNavHost(graph = graph, autoOpenPlayer = autoOpenPlayer)
                        }
                    }
                }
            }
        }

        // Android 13+ 通知权限：不授权也能播，只是没有媒体通知
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}