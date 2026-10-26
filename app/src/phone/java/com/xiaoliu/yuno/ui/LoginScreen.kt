package com.xiaoliu.yuno.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoliu.yuno.core.network.auth.AppleCredentials
import com.xiaoliu.yuno.core.network.auth.CookieHarvester
import com.xiaoliu.yuno.core.ui.component.GlassChip
import com.xiaoliu.yuno.core.ui.theme.YunoAccent
import com.xiaoliu.yuno.core.ui.theme.rememberYunoAmbientBrush
import com.xiaoliu.yuno.core.ui.theme.YunoTextSecondary
import com.xiaoliu.yuno.di.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
// 登录页 —— WebView 走 Apple 真实域名（music.apple.com），用户直接在 Apple
// 页面上输凭据（不经过我们任何代码），我们只在登录完成后收割 Cookie。
//
// 关键实现细节（调研确证，踩坑重灾区）：
//   1) media-user-token 是 HttpOnly Cookie → 只能 CookieManager.getCookie 读，
//      document.cookie 拿不到
//   2) 必须 setAcceptThirdPartyCookies(true)：Apple 登录常在 iframe 内完成，
//      没有顶层跳转，不开这个永远登不上
//   3) onPageFinished 不可靠 → 每秒轮询 CookieJar 双保险
//   4) UA 伪装桌面 Safari（移动版页面登录后 cookie 行为不同）
// ============================================================================

private const val SAFARI_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.0 Safari/605.1.15"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(graph: AppGraph) {
    val ambientBrush = rememberYunoAmbientBrush()
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
    var status by remember { mutableStateOf("正在打开 Apple Music 登录页…") }
    var manualDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 轮询收割：登录态出现即落盘进入主界面
    LaunchedEffect(Unit) {
        while (true) {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    CookieManager.getInstance().getCookie("https://music.apple.com")
                }.getOrNull()
            }
            val credentials = CookieHarvester.harvest(raw)
            if (credentials != null) {
                status = "登录成功，正在进入…"
                graph.onLoggedIn(credentials)
                return@LaunchedEffect
            }
            delay(1000)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(ambientBrush)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // 提示栏（2.0.0 Beta6 真机规格）：
            //   · 只留一句话「凭据只保存在本地」，去掉那串冗长说明
            //   · 底色不再用纯黑 —— 换成圆角卡片 + 微透表面，跟整体质感统一
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.12f),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "登录 Apple Music",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Yuno v$versionName",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.35f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "凭据只保存在本地",
                    style = MaterialTheme.typography.bodySmall,
                    color = YunoTextSecondary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = YunoAccent
                )
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = SAFARI_UA
                        CookieManager.getInstance().setAcceptCookie(true)
                        // ★ 第三方 Cookie 开关：Apple 的 iframe 登录态全靠它
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = WebViewClient()
                        loadUrl("https://music.apple.com/")
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassChip(
                    text = "登录不上？手动粘贴媒体令牌",
                    onClick = { manualDialog = true }
                )
            }
        }
    }

    if (manualDialog) {
        ManualTokenDialog(
            onDismiss = { manualDialog = false },
            onConfirm = { token, storefront ->
                manualDialog = false
                scope.launch {
                    graph.onLoggedIn(
                        AppleCredentials(
                            mediaUserToken = token,
                            storefront = storefront.ifBlank { "us" }
                        )
                    )
                }
            }
        )
    }
}

/** 兜底通道：浏览器 DevTools 复制 media-user-token 手动粘贴 */
@Composable
private fun ManualTokenDialog(
    onDismiss: () -> Unit,
    onConfirm: (token: String, storefront: String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var storefront by remember { mutableStateOf("us") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF16161E),
        title = { Text("手动粘贴媒体令牌", color = Color.White) },
        text = {
            Column {
                Text(
                    text = "浏览器登录 music.apple.com 后，开发者工具 → 应用 → Cookie → " +
                        "复制 media-user-token 的值（通常以 0. 开头）",
                    style = MaterialTheme.typography.bodySmall,
                    color = YunoTextSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = { Text("media-user-token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = storefront,
                    onValueChange = { storefront = it },
                    placeholder = { Text("storefront（us / cn / jp…）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (token.isNotBlank()) {
                        onConfirm(token.trim(), storefront.trim().lowercase())
                    }
                }
            ) {
                Text("保存", color = YunoAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = YunoTextSecondary)
            }
        }
    )
}
