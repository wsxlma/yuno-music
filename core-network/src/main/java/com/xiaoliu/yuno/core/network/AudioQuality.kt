package com.xiaoliu.yuno.core.network

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放音质偏好（个人偏好，全局单例 —— AppGraph.start 时注入 Context）。
 *
 * 2.0.0 Beta6：**改用苹果官方口径**，不再用"高/标准、256K/64K"这种自造说法。
 *
 * | 档位 | 官方说法 | 实际规格 |
 * |---|---|---|
 * | [Quality.LOSSLESS] | 无损 | ALAC 48kHz / 24bit |
 * | [Quality.HI_RES_LOSSLESS] | 高解析度无损 | ALAC 192kHz / 24bit |
 *
 * 另有**独立开关** [dolbyAtmosEnabled]（空间音频），与音质档位互不干扰：
 * 开了空间音频时走 Dolby Atmos 流，音质档位作为不支持 Atmos 时的回退。
 */
enum class Quality(val label: String, val spec: String) {
    /** 无损 —— ALAC 48kHz / 24bit */
    LOSSLESS("无损", "48kHz · 24位"),

    /** 高解析度无损 —— ALAC 192kHz / 24bit */
    HI_RES_LOSSLESS("高解析度无损", "192kHz · 24位");

    companion object {
        /** 默认档位：无损（省流且与绝大多数设备匹配） */
        val DEFAULT = LOSSLESS
    }
}

object AudioQuality {

    private const val PREFS_NAME = "yuno_settings"
    private const val KEY_QUALITY = "quality"
    private const val KEY_DOLBY_ATMOS = "dolby_atmos"
    // 旧键（1.0.x 的布尔值）—— 迁移用，读完即弃
    private const val KEY_STANDARD_LEGACY = "prefer_standard"

    /** 当前音质档位 */
    @Volatile
    var quality: Quality = Quality.DEFAULT
        private set

    /** 空间音频（Dolby Atmos）开关 —— 与音质档位独立 */
    @Volatile
    var dolbyAtmosEnabled: Boolean = false
        private set

    @Volatile
    private var prefs: SharedPreferences? = null

    /** App 启动时注入（AppGraph.start） */
    fun init(context: Context) {
        if (prefs == null) {
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            quality = runCatching {
                Quality.valueOf(p.getString(KEY_QUALITY, "") ?: "")
            }.getOrNull() ?: legacyQuality(p)
            dolbyAtmosEnabled = p.getBoolean(KEY_DOLBY_ATMOS, false)
            prefs = p
        }
    }

    /** 1.0.x 的 `prefer_standard` 迁到新档位上 */
    private fun legacyQuality(p: SharedPreferences): Quality =
        if (p.getBoolean(KEY_STANDARD_LEGACY, false)) Quality.LOSSLESS
        else Quality.HI_RES_LOSSLESS

    fun setQuality(value: Quality) {
        quality = value
        prefs?.edit()?.putString(KEY_QUALITY, value.name)?.apply()
        YunoLog.d("Audio", "quality -> ${value.label} (${value.spec})")
    }

    fun setDolbyAtmos(enabled: Boolean) {
        dolbyAtmosEnabled = enabled
        prefs?.edit()?.putBoolean(KEY_DOLBY_ATMOS, enabled)?.apply()
        YunoLog.d("Audio", "dolby atmos -> $enabled")
    }

    /**
     * 实际请求流时的档位代号。
     *
     * 开了 Dolby Atmos 时优先要空间音频流；不支持则按 [quality] 回退。
     */
    fun streamFlavor(): String = when {
        dolbyAtmosEnabled -> "dolby-atmos"
        quality == Quality.HI_RES_LOSSLESS -> "alac-192khz"
        else -> "alac-48khz"
    }
}
