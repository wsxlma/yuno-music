package com.xiaoliu.yuno.core.network

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * 杜比解码能力探测 —— Dolby Atmos 流选择的闸门。
 *
 * Atmos 资产是 E-AC3（JOC）或 AC-4 编码，不是所有设备都有解码器。
 * 没有解码器硬选 Atmos 流 = ExoPlayer 必报错 → 跌进 30 秒 preview 降级，
 * 等于开了开关的人全都听不全 —— 所以排序必须发生在探测之后。
 *
 * 探测一次缓存结果（codec 列表是系统级静态信息，进程生命周期内不会变）。
 */
object DolbyCapability {

    private val ATMOS_MIMES = listOf("audio/eac3-joc", "audio/eac3", "audio/ac4")

    @Volatile
    private var cached: Boolean? = null

    @Volatile
    private var codecs: MediaCodecList? = null

    /** 设备是否能解杜比空间音频（E-AC3 JOC / AC-4 任一即可） */
    fun deviceDecodesAtmos(): Boolean {
        cached?.let { return it }
        val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull()
        codecs = list
        val ok = list != null && ATMOS_MIMES.any { mime -> findDecoder(mime) }
        cached = ok
        YunoLog.d("Atmos", "device decodes E-AC3/AC-4: $ok")
        return ok
    }

    private fun findDecoder(mime: String): Boolean {
        val list = codecs ?: return false
        // 有的设备只报 2 声道 eac3 解码器（立体声下行混音），6/2 任一命中都算能解
        return listOf(6, 2).any { channels ->
            runCatching {
                list.findDecoderForFormat(
                    MediaFormat.createAudioFormat(mime, 48_000, channels)
                )
            }.getOrNull() != null
        }
    }
}