package com.xiaoliu.yuno.feature.lyrics.fx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AColor
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.xiaoliu.yuno.core.model.AudioBands
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.LyricsTimeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest

/**
 * Folia 全屏特效 WebView 渲染层（路线 B 终极版）。
 *
 * 直接加载 Folia 原版 React + framer-motion + CSS 特效（assets/folia/bundle.js），
 * Kotlin 侧通过 evaluateJavascript 单向喂数据：
 *   · 首次 config → [FxWebViewBridge.sendConfig]，在 onPageFinished 时发一次；
 *     切歌/切模式时由 [LaunchedEffect] 补发一次（JS 侧 Folia state merge 幂等，重复调用不闪）。
 *   · 播放进度  → [FxWebViewBridge.sendProgress]（高频，50ms 节流 + pending flag 防积压）
 *
 * 开屏抽搐修复：删掉了原来 200ms×50 的轮询重发（`repeat(50)` 每 200ms 发一次 config，
 * 每次发都会让 Folia 的 softFocus style transition 重触发一次 → 反复闪屏）。
 * 现在只在 onPageFinished 和 key 变化时各发一次，不再轮询。
 *
 * @param timeline     歌词时间轴（null = 无歌词）
 * @param positionFlow 播放进度流（ms）
 * @param mode         Folia visualizer 模式（"classic" / "cadenza" / …）
 * @param songTitle    歌名
 * @param songArtist   歌手
 * @param durationMs   歌曲总时长 ms
 * @param isPlaying    当前是否在播放（每次重组时取最新值）
 * @param glowColor    光晕主色（来自封面取色，映射到 Folia theme.accentColor）
 * @param coverUrl     封面地址（Folia 背景层/圣咏头像来源用，可空）
 * @param bandsFlow    5 频段音频能量流（core-playback 的 AudioBandsAnalyzer），驱动 cadenza/fume/diorama
 */
@Composable
fun FxWebView(
    timeline: LyricsTimeline?,
    positionFlow: Flow<Long>,
    mode: String,
    songTitle: String,
    songArtist: String,
    /** 曲目 id：作为 Folia 的 seed（每首歌一条独立走廊 + 换歌时的转场飞行） */
    songId: String = "",
    durationMs: Long,
    isPlaying: Boolean,
    glowColor: Color,
    coverUrl: String = "",
    bandsFlow: StateFlow<AudioBands>? = null,
    /** 第②层纯色遮罩的不透明度：0 = 封面色全透，1 = 完全压成纯色（越大越暗） */
    bgScrim: Float = 0.8f,
    /** 封面偏白系时整块反色：不用封面色背景，直接深灰黑底 */
    bgInvert: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lines = remember(timeline) { timeline?.allLines().orEmpty() }
    val accentHex = String.format("#%06X", 0xFFFFFF and glowColor.toArgb())

    // 最新 config 快照：每次重组刷新，bridge 发 config 时读它（永远拿到最新值）
    val snap = remember { ConfigSnapshot() }
    snap.lines = lines; snap.mode = mode; snap.songTitle = songTitle
    snap.songArtist = songArtist; snap.songId = songId; snap.durationMs = durationMs
    snap.isPlaying = isPlaying; snap.accentHex = accentHex; snap.coverUrl = coverUrl
    snap.bgScrim = bgScrim
    snap.bgInvert = bgInvert

    // 音频频段：独立协程收集，不触发重组；进度 Job 里读最新值
    val bandsState = remember { mutableStateOf(AudioBands.Silent) }
    LaunchedEffect(bandsFlow) {
        bandsFlow?.collect { bandsState.value = it }
    }

    val bridge = remember(snap) { FxWebViewBridge(snap) }

    // WebView：onPageFinished → bridge 发第一次 config（YunoFeed 此时已注册）
    val webView = remember {
        createWebView(context) { bridge.sendConfig() }
    }
    bridge.bindWebView(webView)

    // ── 进度 Job ──
    DisposableEffect(webView, isPlaying) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val paused = !isPlaying
        val job = scope.launch {
            positionFlow.collectLatest { posMs ->
                val b = bandsState.value
                bridge.sendProgress(
                    currentTimeSec = posMs / 1000.0,
                    paused = paused,
                    bass = b.bass.toDouble(),
                    lowMid = b.lowMid.toDouble(),
                    mid = b.mid.toDouble(),
                    vocal = b.vocal.toDouble(),
                    treble = b.treble.toDouble(),
                )
            }
        }
        onDispose {
            job.cancel()
            scope.cancel()
        }
    }

    // ── Config 重发（切歌 / 切模式 / 换色 / 初始 key 变化）──
    // onPageFinished 已经发过第一次；LaunchedEffect 在 key 真正变化时补发，
    // 两次调用 sendConfig 之间如果 snap 内容没变，JS 侧 Folia 的 state merge 是幂等的，不会闪。
    LaunchedEffect(lines, mode, songTitle, songArtist, durationMs, accentHex) {
        bridge.sendConfig()
    }

    AndroidView(
        factory = { webView },
        modifier = modifier.fillMaxSize(),
    )

    // WebView 销毁
    DisposableEffect(Unit) {
        onDispose {
            bridge.destroy()
            webView.destroy()
        }
    }
}

// ============================================================================
// config 快照 + 去重
// ============================================================================

class ConfigSnapshot {
    @Volatile var lines: List<LyricLine> = emptyList()
    @Volatile var mode: String = "classic"
    @Volatile var songTitle: String = ""
    @Volatile var songArtist: String = ""
    @Volatile var songId: String = ""
    @Volatile var durationMs: Long = 0L
    @Volatile var isPlaying: Boolean = false
    @Volatile var accentHex: String = "#FA586A"
    @Volatile var coverUrl: String = ""
    @Volatile var bgScrim: Float = 0.8f
    @Volatile var bgInvert: Boolean = false
}

// ============================================================================
// WebView 创建
// ============================================================================

@SuppressLint("SetJavaScriptEnabled", "UnusedAttribute")
private fun createWebView(context: Context, onPageFinished: (() -> Unit)? = null): WebView =
    WebView(context).apply {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            saveFormData = false
            savePassword = false
            // ★ 视口策略（2026-10-28 2.6.3-r 统一）：
            //   index.html 里 viewport 固定 width=1600，靠 WebView 整体缩放到物理屏幕。
            //   必须 useWideViewPort + loadWithOverviewMode 都开，否则 WebView 会忽略
            //   viewport meta 用默认窄视口（~830 CSS px），Folia 各模式的 px 硬顶失效，
            //   尺寸全乱（心环过大 / 摆钟出屏 / 特效只占中间一条）。
            setUseWideViewPort(true)
            loadWithOverviewMode = true
            builtInZoomControls = false
            setSupportZoom(false)
            // 钉死文字缩放 100%：WebView 默认跟随系统字体大小，
            // 系统字号调过之后 Folia 的 rem 基准会整体漂移（"有的太大有的太小"的来源之一）
            textZoom = 100
        }
        // 透明背景（Compose 封面模糊背景透出来）
        setBackgroundColor(AColor.TRANSPARENT)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                onPageFinished?.invoke()
            }
        }
        webChromeClient = WebChromeClient()
        loadUrl("file:///android_asset/folia/index.html")
    }

// ============================================================================
// JS Bridge
//
// Folia bundle 契约（android-webview/main.tsx → YunoFeed）：
//   YunoFeed.onProgress(sec, paused, bass, lowMid, mid, vocal, treble)
//   YunoFeed.onConfig(jsonString)   ← 传单个 JSON 字符串，JS 侧 JSON.parse
// ============================================================================

class FxWebViewBridge(private val snap: ConfigSnapshot) {

    @Volatile private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastProgressMs = 0L
    @Volatile private var progressPending = false
    private val progressIntervalMs = 50L
    fun bindWebView(wv: WebView) {
        webView = wv
    }

    /**
     * 发一次 config（onPageFinished / LaunchedEffect 调用）。
     * 读 snap 里最新值，构造 JSON 喂给 YunoFeed。
     * JS 侧 Folia 的 state merge 对重复相同值幂等，重复调用不会闪屏。
     */
    fun sendConfig() {
        val wv = webView ?: return
        mainHandler.post {
            val json = buildConfigJson()
            val jsStringLiteral = json
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            val jsCall = "if(window.YunoFeed)YunoFeed.onConfig('$jsStringLiteral');"
            runCatching {
                wv.evaluateJavascript(jsCall, null)
            }
        }
    }

    /**
     * 发送播放进度（50ms 节流 + pending flag 防积压）。
     *
     * @param bass/lowMid/mid/vocal/treble 可选 5 频段能量 0..1，
     *   不传时 Folia 侧按 0 处理（cadenza/fume 仍有基础动画，不靠音频驱动的部分正常显示）
     */
    fun sendProgress(
        currentTimeSec: Double,
        paused: Boolean,
        bass: Double = 0.0,
        lowMid: Double = 0.0,
        mid: Double = 0.0,
        vocal: Double = 0.0,
        treble: Double = 0.0,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastProgressMs < progressIntervalMs) return
        if (progressPending) return
        lastProgressMs = now
        progressPending = true
        val wv = webView ?: return
        mainHandler.post {
            progressPending = false
            runCatching {
                wv.evaluateJavascript(
                    "if(window.YunoFeed)YunoFeed.onProgress($currentTimeSec,$paused,$bass,$lowMid,$mid,$vocal,$treble);",
                    null
                )
            }
        }
    }

    /** 销毁：清掉 pending 状态 */
    fun destroy() {
        progressPending = false
    }

    // ── JSON 构造（读 snap 里最新值）──

    private fun buildConfigJson(): String {
        val linesJson = snap.lines.joinToString(",", "[", "]") { line ->
            val wordsJson = line.words.joinToString(",", "[", "]") { w ->
                """{"text":"${jsEsc(w.text)}","startTime":${w.startMs / 1000.0},"endTime":${w.endMs / 1000.0}}"""
            }
            """{"words":$wordsJson,"startTime":${line.startMs / 1000.0},"endTime":${line.endMs / 1000.0},"fullText":"${jsEsc(line.plainText)}"}"""
        }

        val titleJson = if (snap.songTitle.isEmpty()) "null" else "\"${jsEsc(snap.songTitle)}\""
        val artistJson = if (snap.songArtist.isEmpty()) "null" else "\"${jsEsc(snap.songArtist)}\""
        val coverJson = if (snap.coverUrl.isEmpty()) "null" else "\"${jsEsc(snap.coverUrl)}\""
        // accentColor = 封面取色；secondaryColor = primaryColor（去蓝字预告）；
        // backgroundColor = transparent（Compose 层控制底色）
        val themeJson = """{"name":"yuno","backgroundColor":"transparent","primaryColor":"#FFFFFF","accentColor":"${snap.accentHex}","secondaryColor":"#FFFFFF","fontStyle":"sans","animationIntensity":"normal"}"""

        return """{"lines":$linesJson,"mode":"${jsEsc(snap.mode)}","songTitle":$titleJson,"songArtist":$artistJson,"songId":"${jsEsc(snap.songId)}","coverUrl":$coverJson,"durationSec":${snap.durationMs / 1000.0},"currentTimeSec":0.0,"paused":${!snap.isPlaying},"theme":$themeJson,"bgScrim":${snap.bgScrim},"bgInvert":${snap.bgInvert}}"""
    }

    private fun jsEsc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
