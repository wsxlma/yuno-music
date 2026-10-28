package com.xiaoliu.yuno.core.model

/**
 * 播放音频的 5 频段能量（0..1），供 Folia 全屏特效驱动（cadenza/fume/diorama 等）。
 *
 * 由 core-playback 的 [AudioBandsAnalyzer] 从 ExoPlayer 音频管线实时计算，
 * 经 FxWebView 的 YunoFeed.onProgress 喂给 WebView 里的 Folia。
 */
data class AudioBands(
    val bass: Float = 0f,
    val lowMid: Float = 0f,
    val mid: Float = 0f,
    val vocal: Float = 0f,
    val treble: Float = 0f
) {
    companion object {
        val Silent = AudioBands()
    }
}