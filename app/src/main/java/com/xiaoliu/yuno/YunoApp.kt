package com.xiaoliu.yuno

import android.app.Application
import com.xiaoliu.yuno.di.AppGraph

class YunoApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        installCrashRecorder()
    }

    /**
     * 事故黑匣子：任何未捕获异常都写一份到 crash.txt（下次启动时
     * TV 界面直接显示给用户看 —— TV 上没有 adb/logcat，这是唯一取证手段）。
     */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sb = StringBuilder()
                sb.append("thread=").append(thread.name).append('\n')
                var t: Throwable? = throwable
                var depth = 0
                while (t != null && depth < 8) {
                    sb.append(t::class.java.name)
                        .append(": ").append(t.message).append('\n')
                    t.stackTrace.take(30).forEach { sb.append("    at ").append(it).append('\n') }
                    t = t.cause
                    depth++
                    if (t != null) sb.append("caused by: ")
                }
                java.io.File(filesDir, "crash.txt")
                    .writeText(sb.toString(), Charsets.UTF_8)
            }
            previous?.uncaughtException(thread, throwable)
            // 没挂默认处理器就直接杀掉，走系统默认兜底
            if (previous == null) {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
