package com.xiaoliu.yuno.core.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.network.YunoLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * 流地址解析缝 —— data 层实现，core-playback 不认识 Apple。
 */
interface StreamResolver {
    /** 尽力拿可播地址：全曲 lease 优先，失败自动退 30 秒 preview；彻底失败返回 null */
    suspend fun resolve(track: Track): Track?

    /**
     * 播放错误后的降级解析 —— 只试未加密源（preview）。
     * 触发场景：webPlayback 拿到的全曲 HLS 是 Widevine 加密的，
     * ExoPlayer 解不了 → onPlayerError → 用这里换一条能响的流。
     */
    suspend fun resolveFallback(track: Track): Track?
}

/**
 * 播放控制器 —— UI 与 PlaybackService 之间的唯一命令通道（MediaController 封装）。
 *
 * 线程约定：所有命令方法要求在主线程调用（本 App 的调用点全在 UI 线程）。
 * 状态读取不经过这里 —— 统一走 PlaybackStateHub.state（唯一事实源）。
 */
class PlaybackController(
    private val context: Context,
    private val resolver: StreamResolver
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var connectFuture: ListenableFuture<MediaController>? = null

    @Volatile
    private var controller: MediaController? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 已经降级过的曲目（防止"错误→降级→再错误"死循环） */
    private val degradedIds: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private companion object {
        /** 后台解析批次大小 */
        const val RESOLVE_BATCH = 4
    }

    /** 在主线程调用（Application.onCreate 里） */
    fun connect() {
        if (connectFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connectFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            controller = c
            _connected.value = c != null
            c?.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    // 诊断日志：全曲 DRM/manifest 失败现场证据 ——
                    // 错误码 + 完整 cause 链 + 最深层异常的堆栈（NPE 定位靠它）
                    val chain = buildString {
                        var t: Throwable? = error
                        var depth = 0
                        while (t != null && depth < 5) {
                            if (depth > 0) append(" <- ")
                            append(t::class.simpleName).append(": ").append(t.message)
                            t = t.cause
                            depth++
                        }
                    }
                    var deepest: Throwable? = error
                    while (deepest?.cause != null && deepest.cause !== deepest) {
                        deepest = deepest.cause
                    }
                    val trace = deepest?.stackTrace
                        ?.take(14)
                        ?.joinToString(separator = "\n    at ")
                        .orEmpty()
                    YunoLog.e(
                        "Player",
                        "onPlayerError ${error.errorCodeName}: $chain\n    at $trace"
                    )
                    handlePlayerError()
                }
            })
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        connectFuture?.let { runCatching { MediaController.releaseFuture(it) } }
        connectFuture = null
        controller = null
        _connected.value = false
    }

    // ---------------- 播放队列 ----------------

    suspend fun play(track: Track) = playQueue(listOf(track), 0)

    /**
     * 开播快路径：只等「目标曲」解析完就出声（资料库 300 首也不会让首播等 300 次请求）。
     * 队列 Timeline 一次性装满（其余曲目 uri 先留空），后台分批解析后原地 replaceMediaItem 补上——
     * 万一播到还没解析到的曲目，onPlayerError 的降级链会兜住（preview），不会哑火。
     */
    suspend fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val c = controller ?: return
        // ⚠️ 攻击性检查（Beta14）：tracks 为空时 coerceIn(0, -1) 会抛异常
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        val target = tracks[index]

        val first = if (target.streamUrl.isNotBlank()) target
        else runCatching { resolver.resolve(target) }.getOrNull()
        if (first == null || first.streamUrl.isBlank()) return

        val items = tracks.mapIndexed { i, t ->
            (if (i == index) first else t).toMediaItem()
        }
        c.setMediaItems(items, index, 0L)
        c.prepare()
        c.play()

        backgroundResolveRemaining(c, tracks, index)
    }

    // ---------------- 队列编辑（播放队列面板：拖动排序 / 移出） ----------------

    /**
     * 加入待播列表（真机需求）：追加到队列末尾，不打断当前播放。
     * 官方接口 MediaController.addMediaItem：
     *   先插一个元数据占位项（streamUrl 留空），后台 resolve 流地址后
     *   replaceMediaItem 原地补档。还没补档就轮到它时，现有 onPlayerError
     *   降级链兜底（preview），不会哑火。
     * 队列为空 = 没在播 → 直接开播它。
     */
    fun addToQueue(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            scope.launch { play(track) }
            return
        }
        val index = c.mediaItemCount          // 插入位置 = 末尾
        c.addMediaItem(track.toMediaItem())
        scope.launch {
            val resolved = runCatching { resolver.resolve(track) }.getOrNull() ?: return@launch
            if (index < c.mediaItemCount) {
                c.replaceMediaItem(index, resolved.toMediaItem())
            }
        }
    }

    /**
     * 插播（真机需求）：插到当前曲的下一位置，当前播完就轮到它。
     * 实现同 [addToQueue]，只是插入下标 = 当前曲 + 1。
     */
    fun playNext(track: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            scope.launch { play(track) }
            return
        }
        val index = (c.currentMediaItemIndex + 1).coerceIn(1, c.mediaItemCount)
        c.addMediaItem(index, track.toMediaItem())
        scope.launch {
            val resolved = runCatching { resolver.resolve(track) }.getOrNull() ?: return@launch
            if (index < c.mediaItemCount) {
                c.replaceMediaItem(index, resolved.toMediaItem())
            }
        }
    }

    /** 拖动排序：把第 from 项挪到第 to 项（MediaController 必须在主线程调用） */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from !in 0 until c.mediaItemCount) return
        if (to !in 0 until c.mediaItemCount) return
        if (from == to) return
        c.moveMediaItem(from, to)
    }

    /** 移出队列：删掉第 index 项（删的是当前曲时 Media3 自动顺延到下一首） */
    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
    }

    /** 后台补给：小批（4 首）解析剩余曲目，逐批 replaceMediaItem 填进队列 */
    private fun backgroundResolveRemaining(
        c: MediaController,
        tracks: List<Track>,
        skipIndex: Int
    ) {
        val pending = tracks.withIndex()
            .filter { (i, t) -> i != skipIndex && t.streamUrl.isBlank() }
        if (pending.isEmpty()) return

        scope.launch {
            for (chunk in pending.chunked(RESOLVE_BATCH)) {
                val done = coroutineScope {
                    chunk.map { (i, t) ->
                        async(Dispatchers.IO) {
                            i to runCatching { resolver.resolve(t) }.getOrNull()
                        }
                    }.awaitAll()
                }
                // awaitAll 恢复回 Main（scope 的调度器），逐个替换是主线程调用 ✓
                done.forEach { (i, resolved) ->
                    if (resolved != null && i in 0 until c.mediaItemCount) {
                        runCatching { c.replaceMediaItem(i, resolved.toMediaItem()) }
                    }
                }
            }
        }
    }

    /**
     * 播放错误 → 全曲加密流播放不了 → 悄悄换 30 秒 preview 继续唱。
     * 每首只降级一次。
     * 任何异常（controller 断开 / Media3 内部错）一律不崩 ——
     * 真机 bug：放歌放一半闪退，根因候选即此处的裸调用。
     */
    private fun handlePlayerError() {
        val c = controller ?: return
        val track = runCatching { PlaybackStateHub.current.currentTrack }.getOrNull() ?: return
        if (!degradedIds.add(track.id)) return
        scope.launch {
            runCatching {
                val fallback = runCatching { resolver.resolveFallback(track) }.getOrNull() ?: return@launch
                val index = c.currentMediaItemIndex
                if (index in 0 until c.mediaItemCount) {
                    c.replaceMediaItem(index, fallback.toMediaItem())
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    // ---------------- 传输控制（主线程调用） ----------------

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun prev() {
        controller?.let { c ->
            if (c.hasPreviousMediaItem()) c.seekToPreviousMediaItem() else c.seekTo(0L)
        }
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let { c ->
            c.repeatMode = when (c.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }
}
