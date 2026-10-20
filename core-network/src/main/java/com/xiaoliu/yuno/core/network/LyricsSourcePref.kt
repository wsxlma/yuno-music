package com.xiaoliu.yuno.core.network

import android.content.Context
import android.content.SharedPreferences
import com.xiaoliu.yuno.core.model.LyricsSource

/**
 * 歌词源偏好（真机需求：设置弹窗里自由选歌词源/获取方式）。
 *
 * 四种模式：
 *   AUTO：Apple 私有歌词（TTML 逐字）优先，失败/时间轴坏 → LRCLIB → 网易云（默认）
 *   APPLE_ONLY：只走 Apple（拿不到就无歌词）
 *   LRCLIB_ONLY：只走 LRCLIB（增强 LRC 优先、普通 LRC 次之）
 *   NETEASE_ONLY：只走网易云（官方 Web API：歌名+歌手搜换 ID 再取词）
 *
 * 选择结果持久化在 yuno_settings（与 AudioQuality 同一张表）。
 * [allows] 给 LyricsRepository 的缓存命中做源过滤：切了模式，
 * 老缓存里来自禁用源的歌词不再被采纳，按新模式重取。
 */
object LyricsSourcePref {

    enum class Mode { AUTO, APPLE_ONLY, LRCLIB_ONLY, NETEASE_ONLY }

    private const val PREFS_NAME = "yuno_settings"
    private const val KEY_MODE = "lyrics_source_mode"

    @Volatile
    var mode: Mode = Mode.AUTO
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    /** App 启动时注入（AppGraph.start，和 AudioQuality 同处） */
    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            mode = runCatching {
                Mode.valueOf(p.getString(KEY_MODE, Mode.AUTO.name) ?: Mode.AUTO.name)
            }.getOrDefault(Mode.AUTO)
            prefs = p
        }
    }

    fun setMode(m: Mode) {
        mode = m
        prefs?.edit()?.putString(KEY_MODE, m.name)?.apply()
    }

    /** 当前模式下，这个源的歌词是否可采纳（缓存命中过滤用） */
    fun allows(source: LyricsSource): Boolean = when (mode) {
        Mode.AUTO -> true
        Mode.APPLE_ONLY -> source == LyricsSource.APPLE_TTML
        Mode.LRCLIB_ONLY -> source in setOf(LyricsSource.LRCLIB_ENHANCED, LyricsSource.LRCLIB_LRC)
        Mode.NETEASE_ONLY -> source == LyricsSource.NETEASE
    }
}