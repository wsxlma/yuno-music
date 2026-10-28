package com.xiaoliu.yuno.core.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.xiaoliu.yuno.core.model.PlaybackState
import com.xiaoliu.yuno.core.network.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放服务 —— 全 App 唯一持有 ExoPlayer 的地方。
 *
 * 职责：
 *  1. 创建 / 持有 ExoPlayer（HLS 流，Apple 的网络指纹伪装）
 *  2. MediaSession：锁屏控制、媒体通知、蓝牙耳机按键
 *  3. 50ms 采样播放进度 + 全量状态变化，把唯一的真相写进 [PlaybackStateHub]
 *
 * 音频焦点：setAudioAttributes(attrs, handleAudioFocus = true) ——
 * 来电、其他 App 抢占时 ExoPlayer 自动 pause；拔耳机（becoming noisy）同样自动暂停。
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            // 音频管线挂 Tee：解码后的 PCM 实时算 5 频段能量（Folia 全屏特效驱动用）
            .setRenderersFactory(TeeRenderersFactory(this))
            .setMediaSourceFactory(buildMediaSourceFactory())
            .build()

        player.addListener(stateListener)

        mediaSession = MediaSession.Builder(this, player)
            // 点击通知"正在播放"卡片 → 拉起 App 并定位到歌词页
            // （Media3 默认没设 sessionActivity，点击通知只是把 App 拉到前台、不跳转）
            .setSessionActivity(
                buildNotificationIntent()
            )
            .build()

        startTicker()
    }

    /**
     * 通知点击 Intent：MainActivity + extra "open_lyrics"=true →
     * App 启动后直接进歌词页（Activity 读 extra 决定是否打开 FullPlayerScreen）。
     * 用包名+类名（core-playback 不依赖 app 模块，没法直接引 MainActivity）。
     */
    private fun buildNotificationIntent(): PendingIntent {
        val intent = Intent().apply {
            setClassName("com.xiaoliu.yuno", "com.xiaoliu.yuno.MainActivity")
            putExtra("open_lyrics", true)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * 媒体源工厂 —— Widevine DRM 会话挂在它上面（Media3 1.11 的 ExoPlayer.Builder
     * 没有 DRM 入口）。未配置凭据时给 DRM_UNSUPPORTED，preview 等未加密流不受影响。
     */
    private fun buildMediaSourceFactory(): DefaultMediaSourceFactory {
        val factory = DefaultMediaSourceFactory(buildDataSourceFactory())
        val drm = AppleWidevine.drmSessionManager(this)
        factory.setDrmSessionManagerProvider { drm }
        return factory
    }

    /**
     * Apple 的 HLS 节点会校验来源：Origin / Referer / UA 都要装成网页播放器。
     * 复用 core-network 的 OkHttp 单例（连接池共享）。
     * 外层再包 [StreamRewriterDataSource]：把 cbcs 清单重写成 Media3 能解的形式
     * （全曲解锁的关键一环），其余数据原样透传。
     */
    private fun buildDataSourceFactory(): DataSource.Factory {
        val defaultHeaders = mapOf(
            "User-Agent" to WEB_PLAYER_UA,
            "Origin" to "https://music.apple.com",
            "Referer" to "https://music.apple.com/"
        )
        val base = OkHttpDataSource.Factory(Http.client)
            .setDefaultRequestProperties(defaultHeaders)
        return DataSource.Factory { StreamRewriterDataSource(base.createDataSource()) }
    }

    // ---------------- 状态上报 ----------------

    private val stateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = pushFullState()

        // ★ 时间轴变化（2026-10-28 2.6.12）：加入待播列表 / 插播 / 队列拖动排序 / 移出队列
        //   改的都是**时间轴**，但这些操作不会触发上面任何一个已注册的事件
        //   （当前曲目没变、播放状态没变、位置也没断）→ 队列内容从来没被推出去，
        //   UI 看到的还是旧队列，于是表现为"加入待播列表 / 插播失效"。
        //   顺带把队列面板的「拖动排序」「移出」一起修了（它们同样只改时间轴）。
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = pushFullState()

        override fun onIsPlayingChanged(isPlaying: Boolean) = pushFullState()

        override fun onPlaybackStateChanged(playbackState: Int) = pushFullState()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = pushFullState()

        override fun onRepeatModeChanged(repeatMode: Int) = pushFullState()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) = pushFullState()

        override fun onPlayerError(error: PlaybackException) {
            pushFullState()
        }
    }

    /** 全量状态快照（不含高频进度，进度由 ticker 补） */
    private fun pushFullState() {
        val queue = if (player.mediaItemCount > 0) {
            (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
        } else {
            emptyList()
        }
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        PlaybackStateHub.update {
            PlaybackState(
                currentTrack = player.currentMediaItem?.toTrack(),
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode.toYunoRepeat(),
                queue = queue,
                queueIndex = player.currentMediaItemIndex
            )
        }
    }

    /** 进度采样：播放中每 50ms 一次（需求钦定节奏） */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                    PlaybackStateHub.update { prev ->
                        prev.copy(
                            positionMs = pos.coerceAtLeast(0L),
                            durationMs = duration ?: prev.durationMs
                        )
                    }
                    delay(TICK_INTERVAL_MS)
                } else {
                    // 暂停/缓冲期间进度不变（推流全停在 pushFullState）——
                    // 250ms 才醒一次，省电且不影响"播放中 50ms"的节奏
                    delay(IDLE_CHECK_MS)
                }
            }
        }
    }

    // ---------------- MediaSessionService 生命周期 ----------------

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉：没在放歌就收工，放歌中则保持（通知里还能控制）
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        ticker?.cancel()
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        PlaybackStateHub.update { PlaybackState() }
        super.onDestroy()
    }

    companion object {
        private const val TICK_INTERVAL_MS = 50L
        private const val IDLE_CHECK_MS = 250L

        /** 伪装成 macOS Chrome 上的 Apple Music 网页播放器 */
        private const val WEB_PLAYER_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}
