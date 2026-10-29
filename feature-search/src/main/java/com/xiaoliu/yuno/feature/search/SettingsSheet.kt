package com.xiaoliu.yuno.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaoliu.yuno.core.network.DolbyCapability
import com.xiaoliu.yuno.core.network.LyricsSourcePref
import com.xiaoliu.yuno.core.network.Quality
import com.xiaoliu.yuno.core.ui.component.LyricFont

/**
 * 设置面板（2.0.0 Beta3 重排版 —— **模块化卡片 + 大圆角 + 清新简约**）。
 *
 * 视觉规格（真机反馈）：「模块排版，大圆角、圆润大矩形，清新简约」
 *   · 每个设置组 = 一张**独立卡片**，圆角 24dp，卡之间留 12dp 呼吸
 *   · 卡内：小图标 + 标题在上，选项在下，留白宽松
 *   · 选项用 **FilterChip**（自带选中态/涟漪/触达高度）
 *   · 危险操作单独一张卡，用 error 色，且走**二次确认**
 *   · 颜色全走 `MaterialTheme.colorScheme`，不硬编码
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    sheetState: SheetState,
    versionName: String,
    quality: Quality,
    onSelectQuality: (Quality) -> Unit,
    dolbyAtmos: Boolean,
    onToggleDolbyAtmos: (Boolean) -> Unit,
    lyricsSourceMode: LyricsSourcePref.Mode,
    onSelectLyricsSource: (LyricsSourcePref.Mode) -> Unit,
    lyricFontPreset: LyricFont.Preset,
    onSelectLyricFont: (LyricFont.Preset) -> Unit,
    onExportLogs: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    var confirmLogout by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 标题 ──
            Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 8.dp)) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Yuno v$versionName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── 播放音质（2.0.0 Beta6：改用苹果官方口径）──
            SettingCard(icon = Icons.Outlined.MusicNote, title = "音频质量") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow {
                        SettingChip(
                            "无损 · 48kHz · 24位",
                            selected = quality == Quality.LOSSLESS
                        ) { onSelectQuality(Quality.LOSSLESS) }
                    }
                    ChipRow {
                        SettingChip(
                            "高解析度无损 · 192kHz · 24位",
                            selected = quality == Quality.HI_RES_LOSSLESS
                        ) { onSelectQuality(Quality.HI_RES_LOSSLESS) }
                    }

                    // Dolby Atmos 独立开关（与音质档位互不干扰）
                    Spacer(Modifier.height(4.dp))
                    val deviceAtmos = remember { DolbyCapability.deviceDecodesAtmos() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "杜比全景声",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                // 探测结果直接写进说明，开着开关的人一眼知道走没走 Atmos
                                text = if (deviceAtmos) "空间音频流；不支持时回退到上方音质档位"
                                else "此设备无 E-AC3/AC-4 解码器，开启后仍走上方音质档位",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = dolbyAtmos,
                            onCheckedChange = onToggleDolbyAtmos
                        )
                    }
                }
            }

            // ── 歌词源 ──
            SettingCard(icon = Icons.Outlined.Subtitles, title = "歌词源") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow {
                        SettingChip("自动", selected = lyricsSourceMode == LyricsSourcePref.Mode.AUTO) {
                            onSelectLyricsSource(LyricsSourcePref.Mode.AUTO)
                        }
                        SettingChip("仅 Apple", selected = lyricsSourceMode == LyricsSourcePref.Mode.APPLE_ONLY) {
                            onSelectLyricsSource(LyricsSourcePref.Mode.APPLE_ONLY)
                        }
                    }
                    ChipRow {
                        SettingChip("仅 LRCLIB", selected = lyricsSourceMode == LyricsSourcePref.Mode.LRCLIB_ONLY) {
                            onSelectLyricsSource(LyricsSourcePref.Mode.LRCLIB_ONLY)
                        }
                        SettingChip("仅网易云", selected = lyricsSourceMode == LyricsSourcePref.Mode.NETEASE_ONLY) {
                            onSelectLyricsSource(LyricsSourcePref.Mode.NETEASE_ONLY)
                        }
                    }
                }
            }

            // ── 歌词字体 ──
            SettingCard(icon = Icons.Outlined.TextFields, title = "歌词字体") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChipRow {
                        SettingChip("系统默认", selected = lyricFontPreset == LyricFont.Preset.SYSTEM) {
                            onSelectLyricFont(LyricFont.Preset.SYSTEM)
                        }
                        SettingChip("内置字体", selected = lyricFontPreset != LyricFont.Preset.SYSTEM) {
                            onSelectLyricFont(LyricFont.Preset.SF_PRO)
                        }
                    }
                    if (lyricFontPreset != LyricFont.Preset.SYSTEM) {
                        ChipRow {
                            SettingChip("SF Pro", selected = lyricFontPreset == LyricFont.Preset.SF_PRO) {
                                onSelectLyricFont(LyricFont.Preset.SF_PRO)
                            }
                            SettingChip("PingFang SC", selected = lyricFontPreset == LyricFont.Preset.PING_FANG) {
                                onSelectLyricFont(LyricFont.Preset.PING_FANG)
                            }
                        }
                    }
                }
            }

            // ── 动作卡 ──
            ActionCard(
                icon = Icons.Outlined.BugReport,
                title = "导出日志",
                subtitle = "导出运行记录用于排查（不含账号令牌等敏感信息）",
                onClick = onExportLogs
            )

            ActionCard(
                icon = Icons.AutoMirrored.Outlined.Logout,
                title = "退出登录",
                subtitle = "清除本机保存的 Apple 账号凭据",
                danger = true,
                onClick = { confirmLogout = true }
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            shape = RoundedCornerShape(28.dp),
            title = { Text("退出登录？") },
            text = { Text("会清除本机保存的媒体令牌与开发者令牌，下次需要重新登录。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            }
        )
    }
}

// ============================================================================
// 卡片
// ============================================================================

/** 一个设置分组卡片：图标 + 标题 + 内容，24dp 大圆角 */
@Composable
private fun SettingCard(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

/** 可点击的动作卡片（导出日志 / 退出登录） */
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 横向排列的选项芯片 */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/** M3 选项芯片 */
@Composable
private fun SettingChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}
