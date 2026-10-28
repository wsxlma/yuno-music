package com.xiaoliu.yuno.feature.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * 事故黑匣子的屏幕出口 —— TV 没有 adb/logcat，闪退取证只能靠它：
 * 上次崩溃把堆栈写进 filesDir/crash.txt（见 YunoApp.installCrashRecorder），
 * 本组件下次启动时全屏显示，用户拍屏/抄字发给开发者即可定位。
 *
 * ⚠️ 必须「堵门」用：启动时先查 crash.txt，有记录就**只**显示本组件、
 * 绝不组装正常界面（正常界面可能正是崩溃源，先画它红字永远轮不到）。
 */
@Composable
fun TvCrashBanner(text: String, onClear: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF2140B0B))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 56.dp, vertical = 44.dp)
        ) {
            Text(
                text = "检测到上次崩溃 —— 请把下面内容拍给开发者：",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = text,
                color = Color(0xFFFFC9C9),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(26.dp))
            Text(
                text = "（读完可清除此记录）→ 确认已发给开发者后点这里",
                color = Color(0xFFBF9A9A),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable { onClear() }
                    .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

/** 读取上次崩溃记录；没有则返回 null（读失败也当没有，绝不在启动路径上抛异常） */
fun readCrashText(context: android.content.Context): String? = runCatching {
    val f = File(context.filesDir, "crash.txt")
    if (f.exists()) f.readText().takeIf { it.isNotBlank() } else null
}.getOrNull()

/** 清除崩溃记录 */
fun clearCrashText(context: android.content.Context) {
    runCatching { File(context.filesDir, "crash.txt").delete() }
}