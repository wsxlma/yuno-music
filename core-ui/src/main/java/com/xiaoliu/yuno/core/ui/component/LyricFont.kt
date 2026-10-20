package com.xiaoliu.yuno.core.ui.component

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * 歌词字体偏好（真机需求：设置弹窗里音质下方加字体选择）。
 *
 * 三种取值：
 *   · SYSTEM：跟随手机系统字体（默认）
 *   · SF_PRO：内置 SF Pro Display Regular（res/font/sf_pro.otf）
 *   · PING_FANG：内置 PingFang SC（res/font/ping_fang_sc.ttf）
 *
 * 架构纪律：偏好放 core-ui（渲染层所在地）——core-ui 不依赖 core-network，
 * 全链路单向（feature 层 → core-ui）。字体文件已内置，不运行时下载。
 * 选择持久化在 yuno_settings（与 AudioQuality 同表，key 独立）。
 */
object LyricFont {

    enum class Preset { SYSTEM, SF_PRO, PING_FANG }

    private const val PREFS_NAME = "yuno_settings"
    private const val KEY_PRESET = "lyric_font_preset"

    // 内置字体族（Compose Font(resId)，首次使用即加载，顶层持实例避免反复构造）
    private val SfProFamily = FontFamily(Font(com.xiaoliu.yuno.core.ui.R.font.sf_pro))
    private val PingFangFamily = FontFamily(Font(com.xiaoliu.yuno.core.ui.R.font.ping_fang_sc))

    @Volatile
    var preset: Preset = Preset.SYSTEM
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    /** App 启动时注入（AppGraph.start，和 AudioQuality 同处） */
    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            preset = runCatching {
                Preset.valueOf(p.getString(KEY_PRESET, Preset.SYSTEM.name) ?: Preset.SYSTEM.name)
            }.getOrDefault(Preset.SYSTEM)
            prefs = p
        }
    }

    fun setPreset(p: Preset) {
        preset = p
        prefs?.edit()?.putString(KEY_PRESET, p.name)?.apply()
    }

    /** 渲染层一行接入：KaraokeStyle.copy(fontFamily = LyricFont.currentFamily()) */
    fun currentFamily(): FontFamily = when (preset) {
        Preset.SYSTEM -> FontFamily.Default
        Preset.SF_PRO -> SfProFamily
        Preset.PING_FANG -> PingFangFamily
    }

    @Composable
    fun family(): FontFamily = currentFamily()
}