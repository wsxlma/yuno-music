package com.xiaoliu.yuno.core.ui

/**
 * 全工程共用的小工具（2026-10-28 收拢）。
 *
 * 之前 formatDuration 在 5 个文件里各抄一份、easeOutCubic 在 2 个文件里各抄一份，
 * 改动永远要改 5 处。现在统一放这里。
 */

/** 毫秒 → `m:ss`（超过一小时也正常：`61:05`） */
fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/** 三次方缓出（0→1） */
fun easeOutCubic(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return 1f - (1f - c) * (1f - c) * (1f - c)
}
