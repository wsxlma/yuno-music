package com.xiaoliu.yuno.core.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.xiaoliu.yuno.core.model.AudioBands
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 5 频段音频能量的对外入口。
 *
 * 与 [AudioBandsAnalyzer]（internal，持有 Media3 类型）分开的原因是
 * core-playback 对 media3 是 implementation 依赖，上层模块看不到
 * `TeeAudioProcessor.AudioBufferSink` 这个 supertype；对外只暴露纯数据流。
 *
 * 生命周期：
 *   · PlaybackService 用 [TeeRenderersFactory] 创建 ExoPlayer，PCM 自动流进来
 *   · 特效页进入时 [setListenerActive](true)，离开时 false —— 没人看不算，省电
 *   · 暂停/停止时输出 Silent
 */
object AudioBandsHub {

    private val _bands = MutableStateFlow(AudioBands.Silent)
    val bands: StateFlow<AudioBands> = _bands.asStateFlow()

    @Volatile internal var listenerActive = false
        private set

    fun setListenerActive(active: Boolean) {
        listenerActive = active
        if (!active) _bands.value = AudioBands.Silent
    }

    internal fun publish(bands: AudioBands) {
        _bands.value = bands
    }
}

/**
 * 播放音频 5 频段能量分析器（Folia 全屏特效驱动）。
 *
 * 实现方式：Media3 的 [TeeAudioProcessor] 挂在音频解码管线上，
 * 直接拿解码后的 PCM（16bit / float），无需任何权限（不用麦克风、不用 RECORD_AUDIO，
 * 比 android.media.audiofx.Visualizer 干净）。
 *
 * 算法：累积 1024 个采样（单声道下混）→ Hann 窗 → radix-2 FFT →
 * 按频段求平均幅度 → 归一化到 0..1：
 *   bass    20–150 Hz
 *   lowMid  150–400 Hz
 *   mid     400–1200 Hz
 *   vocal   1000–3500 Hz
 *   treble  3500 Hz 以上
 *
 * internal：仅由 [TeeRenderersFactory] 在模块内使用，对外走 [AudioBandsHub]。
 */
internal object AudioBandsAnalyzer : TeeAudioProcessor.AudioBufferSink {

    private const val FFT_SIZE = 1024
    /** 每积累这么多个新采样算一次谱（1024 → 约 43Hz 刷新，比 UI 50ms 一帧还快） */
    private const val HOP_SIZE = 1024

    // ── 音频格式（由 flush 提供）──
    @Volatile private var sampleRate = 44100
    @Volatile private var channelCount = 2
    @Volatile private var encoding = C.ENCODING_PCM_16BIT

    // ── 采样累积（只在音频线程访问，无需同步）──
    private val mono = FloatArray(FFT_SIZE)
    private var fill = 0
    private var sinceLastSpectrum = 0

    // ── FFT 预计算表 ──
    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val cosTable = FloatArray(FFT_SIZE / 2)
    private val sinTable = FloatArray(FFT_SIZE / 2)
    private val bitReverse = IntArray(FFT_SIZE)

    init {
        for (i in 0 until FFT_SIZE / 2) {
            cosTable[i] = cos(2.0 * PI * i / FFT_SIZE).toFloat()
            sinTable[i] = sin(2.0 * PI * i / FFT_SIZE).toFloat()
        }
        var bits = 0
        while ((1 shl bits) < FFT_SIZE) bits++
        for (i in 0 until FFT_SIZE) {
            var r = 0
            for (b in 0 until bits) {
                if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            }
            bitReverse[i] = r
        }
    }

    // ========================================================================
    // TeeAudioProcessor.AudioBufferSink
    // ========================================================================

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = if (sampleRateHz > 0) sampleRateHz else 44100
        this.channelCount = channelCount.coerceAtLeast(1)
        this.encoding = encoding
        fill = 0
        sinceLastSpectrum = 0
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!AudioBandsHub.listenerActive) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_FLOAT -> 4
            C.ENCODING_PCM_16BIT -> 2
            else -> return
        }
        val ch = channelCount
        val frameBytes = bytesPerSample * ch
        val src = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        while (src.remaining() >= frameBytes) {
            var sum = 0f
            for (c in 0 until ch) {
                sum += when (bytesPerSample) {
                    2 -> src.short.toFloat() / 32768f
                    else -> src.float
                }
            }
            mono[fill++] = sum / ch
            sinceLastSpectrum++
            if (fill == FFT_SIZE) {
                if (sinceLastSpectrum >= HOP_SIZE) {
                    sinceLastSpectrum = 0
                    publishSpectrum(computeBands())
                }
                // 半重叠窗：保留后一半，滑入新的一半
                System.arraycopy(mono, FFT_SIZE / 2, mono, 0, FFT_SIZE / 2)
                fill = FFT_SIZE / 2
            }
        }
    }

    // ========================================================================
    // 频谱 → 5 频段
    // ========================================================================

    private fun computeBands(): AudioBands {
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            re[bitReverse[i]] = mono[i] * window[i]
        }
        // radix-2 FFT（就地，im 全 0）
        var half = 1
        while (half < FFT_SIZE) {
            val step = FFT_SIZE / (half * 2)
            for (start in 0 until FFT_SIZE step half * 2) {
                var k = 0
                for (i in start until start + half) {
                    val j = i + half
                    val tRe = re[j] * cosTable[k] + im[j] * sinTable[k]
                    val tIm = -re[j] * sinTable[k] + im[j] * cosTable[k]
                    re[j] = re[i] - tRe
                    im[j] = im[i] - tIm
                    re[i] += tRe
                    im[i] += tIm
                    k += step
                }
            }
            half *= 2
        }

        val binHz = sampleRate.toFloat() / FFT_SIZE
        val halfBins = FFT_SIZE / 2

        fun bandEnergy(lowHz: Float, highHz: Float): Float {
            val lo = (lowHz / binHz).toInt().coerceIn(1, halfBins - 1)
            val hi = (highHz / binHz).toInt().coerceIn(lo + 1, halfBins)
            var sum = 0f
            for (k in lo until hi) {
                sum += hypot(re[k], im[k])
            }
            // 幅度 → 0..1：除以 bin 数后放大（经验系数，跟 Folia 的 0..1 带宽匹配），
            // 再开方做感知曲线，低频小数值也能看出跳动
            val avg = sum / (hi - lo)
            return sqrt((avg * 2.2f).coerceIn(0f, 1f))
        }

        return AudioBands(
            bass = bandEnergy(20f, 150f),
            lowMid = bandEnergy(150f, 400f),
            mid = bandEnergy(400f, 1200f),
            vocal = bandEnergy(1000f, 3500f),
            treble = bandEnergy(3500f, 16000f)
        )
    }

    private fun publishSpectrum(b: AudioBands) {
        AudioBandsHub.publish(b)
    }
}

/**
 * RenderersFactory：把 [AudioBandsAnalyzer] 以 TeeAudioProcessor 的形式挂进音频管线。
 *
 * 用 Tee 而不是 android.media.audiofx.Visualizer：
 *   · Visualizer 需要 RECORD_AUDIO 运行时权限（会弹麦克风授权，体验不可接受）
 *   · Tee 直接拿解码后的 PCM，零权限，数据更干净
 *
 * 保留默认的 Sonic（变速不变调）与 SilenceSkipping 处理器，只多挂一个 Tee。
 */
class TeeRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        return DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(
                    arrayOf<AudioProcessor>(TeeAudioProcessor(AudioBandsAnalyzer)),
                    SilenceSkippingAudioProcessor(),
                    SonicAudioProcessor()
                )
            )
            .build()
    }
}