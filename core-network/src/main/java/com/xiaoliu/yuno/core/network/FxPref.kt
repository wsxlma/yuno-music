package com.xiaoliu.yuno.core.network

import android.content.Context
import android.content.SharedPreferences

/**
 * 歌词特效偏好（Folia 照搬系列）。
 *
 * 只有一个入口（用户钦定 2026-10-28 2.6.4）：歌词页右上角菜单里的"全屏特效"。
 * 原"简单特效"（光晕/弹簧/呼吸/涟漪）四项在设置页是空壳（无人消费、实现也已删除），
 * 已连同 UI 一起移除，这里不再保留任何 simple* 字段。
 *
 * 持久化在 yuno_settings（与 AudioQuality / LyricsSourcePref 同一张表）。
 */
object FxPref {

    /**
     * Folia 全屏特效（visualizer）清单。
     *
     * 2026-10-28 2.6.5：用户逐一确认后**删掉 5 个**（烟雾弥散 fume / 莫奈画布 monet /
     * 静止极简 still / 倾斜视差 tilt / 变奏阶梯 partita），保留 10 个。
     * 命名同步换成 Folia 官方口径（流光/克拉达/镜台/花火/绘光/钟摆/商籁/淡彩/心象/群唱），
     * 去掉原来「XX · XX」那种硬凑的双段式。
     *
     * 说明：删掉的 5 个只是从菜单移除，Folia bundle 里的代码仍在（真删要连带改 entry，
     * 收益为零、风险不小）。哪天想放回来，把对应行加回本列表即可。
     *
     */
    data class FxMode(val id: String, val label: String)

    val FULLSCREEN_MODES: List<FxMode> = listOf(
        FxMode("classic", "流光"),
        FxMode("claddagh", "克拉达"),
        FxMode("diorama", "镜台"),
        FxMode("hanabi", "花火"),
        FxMode("lumiere", "绘光"),
        FxMode("pendolo", "钟摆"),
        FxMode("sonnet", "商籁"),
        FxMode("tempera", "淡彩"),
        FxMode("cadenza", "心象"),
        FxMode("cappella", "群唱")
    )

    const val DEFAULT_FULLSCREEN_MODE = "classic"

    private const val PREFS_NAME = "yuno_settings"
    private const val KEY_FULLSCREEN_MODE = "fx_fullscreen_mode"

    /** 当前选中的全屏特效模式 id（进入特效页时用） */
    @Volatile var fullscreenMode: String = DEFAULT_FULLSCREEN_MODE
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            // 老版本可能存过 timeline / albumwall / 已删模式的 id —— 不在当前清单里就回落默认，
            // 否则菜单里一项都不高亮，进特效页也拿不到有效模式。
            val saved = p.getString(KEY_FULLSCREEN_MODE, DEFAULT_FULLSCREEN_MODE)
                ?: DEFAULT_FULLSCREEN_MODE
            fullscreenMode = if (FULLSCREEN_MODES.any { it.id == saved }) saved else DEFAULT_FULLSCREEN_MODE
        }
    }

    fun setFullscreenMode(modeId: String) {
        fullscreenMode = modeId
        prefs?.edit()?.putString(KEY_FULLSCREEN_MODE, modeId)?.apply()
    }

}
