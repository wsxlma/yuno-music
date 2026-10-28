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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import com.xiaoliu.yuno.core.ui.theme.YunoInk
import com.xiaoliu.yuno.core.ui.theme.YunoTheme
import com.xiaoliu.yuno.feature.tv.TvCrashBanner
import com.xiaoliu.yuno.feature.tv.TvLoginPage
import com.xiaoliu.yuno.feature.tv.TvShell
import com.xiaoliu.yuno.feature.tv.clearCrashText
import com.xiaoliu.yuno.feature.tv.readCrashText
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.LocalHazePerformanceMode

/** 电视专版入口（手机专版的 MainActivity 在 phone 源码集里，互不掺杂） */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as YunoApp).graph

        setContent {
            CompositionLocalProvider(
                LocalHazePerformanceMode provides HazePerformanceMode.Performance
            ) {
                YunoTheme {
                    val playback by PlaybackStateHub.state.collectAsStateWithLifecycle()
                    KeepScreenOnInPlayback(playback.isPlaying)

                    val primed by graph.authPrimed.collectAsStateWithLifecycle()
                    val loggedIn by graph.authStore.isLoggedIn
                        .collectAsStateWithLifecycle(initialValue = false)

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(YunoInk)
                    ) {
                        // 事故黑匣子「堵门」：有崩溃记录 → 只显示红字，
                        // 正常界面一步不组装（崩溃源往往就在正常界面里）。
                        val crashContext = LocalContext.current
                        var crashReport by remember {
                            mutableStateOf(readCrashText(crashContext))
                        }

                        if (crashReport != null) {
                            TvCrashBanner(
                                text = crashReport.orEmpty(),
                                onClear = {
                                    clearCrashText(crashContext)
                                    crashReport = null
                                }
                            )
                        } else {
                            when {
                                !primed -> Unit                       // 凭据读取（毫秒级），黑底兜着
                                !loggedIn -> {
                                    val tvVm: com.xiaoliu.yuno.feature.tv.TvViewModel =
                                        viewModel(factory = graph.viewModelFactory)
                                    TvLoginPage(vm = tvVm)
                                }
                                else -> {
                                    val tvVm: com.xiaoliu.yuno.feature.tv.TvViewModel =
                                        viewModel(factory = graph.viewModelFactory)
                                    TvShell(vm = tvVm)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }
}