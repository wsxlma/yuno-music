package com.xiaoliu.yuno.core.network

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量运行日志 —— 双层：内存环形缓冲（400 条）+ 落盘文件。
 *
 * 用途：出问题时在 App 内「导出日志」一键分享，开发者远程定位（不用连 USB）。
 * 隐私红线：绝不打账号令牌（media-user-token / dev token / 完整播放 URL）；
 * 只记录请求 URL（无 token）、响应状态、错误信息与播放链路状态。
 */
object YunoLog {

    private const val MAX_BUFFER_LINES = 400

    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(file: File?) {
        synchronized(lock) {
            logFile = file
            runCatching { file?.parentFile?.mkdirs() }
            appendLineInternal("---- Yuno session start ----")
        }
    }

    fun d(tag: String, msg: String) = appendLineInternal("D [$tag] $msg")

    fun e(tag: String, msg: String) = appendLineInternal("E [$tag] $msg")

    private fun appendLineInternal(line: String) {
        val full = "${fmt.format(Date())} $line"
        synchronized(lock) {
            buffer.addLast(full)
            while (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
            runCatching { logFile?.appendText(full + "\n") }
        }
    }

    fun snapshot(): String = synchronized(lock) { buffer.joinToString("\n") }

    /** 分享前调用：把内存快照补写进文件（保证最新几条也在），返回可分享的文件 */
    fun fileForShare(): File? {
        val f = logFile ?: return null
        runCatching {
            f.parentFile?.mkdirs()
            f.appendText("\n===== snapshot =====\n${snapshot()}\n")
        }
        return if (f.exists()) f else null
    }
}
