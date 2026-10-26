package com.xiaoliu.yuno.feature.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoliu.yuno.core.ui.component.YunoLoadingCircle
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary

/**
 * TV 登录页 —— 局域网登录（真机口径）：
 * 电视起本地端口，手机同网浏览器打开 `http://<TV 的 IP>:<端口>`，
 * 在 Apple 官方页完成登录后令牌自动送回电视（手动粘贴兜底）。
 * 全程不要求电视上有实体键盘，遥控器只需要「开始 / 取消」两个动作。
 */
@Composable
fun TvLoginPage(vm: TvViewModel) {
    val lan by vm.lan.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        TvFlowBackground(track = null, modifier = Modifier.fillMaxSize())

        Column(
            Modifier.fillMaxSize().padding(64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Yuno TV",
                color = YunoAccent,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "登录以继续",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.height(44.dp))

            when (val s = lan) {
                is TvViewModel.LanState.Idle -> {
                    Text(
                        text = "手机连上同一网络，用浏览器打开电视给出的地址即可登录",
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(28.dp))
                    TvActionChip("开始局域网登录") { vm.startLanLogin() }
                }

                is TvViewModel.LanState.Waiting -> {
                    Text(
                        text = "在手机浏览器打开：",
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = s.url,
                        color = YunoAccent,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(30.dp))
                    Text(
                        text = "登录 Apple Music 后，令牌会自动送回电视",
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(16.dp))
                    YunoLoadingCircle(size = 34.dp)
                    Spacer(Modifier.height(28.dp))
                    TvActionChip("取消") { vm.cancelLanLogin() }
                }

                is TvViewModel.LanState.Error -> {
                    Text(
                        text = s.message,
                        color = YunoTextSecondary,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(28.dp))
                    TvActionChip("重试") { vm.startLanLogin() }
                }

                is TvViewModel.LanState.Done -> {
                    Text(
                        text = "已登录 ✨",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
            }

            // 环境自检（真机定位用）：一眼确认盒子把本 App 当成电视版没有
            val cfg = LocalConfiguration.current
            val uiMode = cfg.uiMode and Configuration.UI_MODE_TYPE_MASK
            val isTvMode = uiMode == Configuration.UI_MODE_TYPE_TELEVISION
            Spacer(Modifier.height(48.dp))
            Text(
                text = "环境：${if (isTvMode) "TV 判定 ✓ 电视版" else "非 TV ✗ 走了手机版"} · " +
                    "API ${Build.VERSION.SDK_INT} · uiMode=$uiMode · " +
                    "密度 ${LocalDensity.current.density}",
                color = YunoTextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}