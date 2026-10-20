package com.xiaoliu.yuno.core.model

/**
 * 歌词时间轴 —— 「positionMs → 当前行/当前字」的唯一换算入口。
 *
 * 全部用二分查找：seek 跳转后歌词重算当前行也走这里，
 * UI 层不允许自己写查找逻辑，保证行为一致。
 */
class LyricsTimeline(val lines: List<LyricLine>) {

    private val sorted: List<LyricLine> = lines.sortedBy { it.startMs }

    val size: Int get() = sorted.size

    fun lineAt(index: Int): LyricLine? = sorted.getOrNull(index)

    fun allLines(): List<LyricLine> = sorted

    /**
     * 二分：positionMs 落在哪一行（≤ positionMs 的最后一行），无歌词返回 -1。
     *
     * 1.0.75 闪退根治（真机"放同一句的下一行就闪退"）：
     *   · 行级歌词（isLineLevel）：startMs = 行起，但行内只挂一个字（覆盖整行文本），
     *     该字的 endMs 可能 < 下一行 startMs → 唱到"字结束与下一行起点之间"时
     *     二分会落在字上（wordIndexAt 对字时间域二分），而 indexOfLineAt 对行时间域二分
     *     已切到下一行 → currentIndex 与 wordIndexAt 错位 → KaraokeLine 里
     *     `wordCells[wordIndex]` 越界（字已属于上一行，但被当成下一行的下标）→ 闪退。
     *   · 修法：行级歌词不参与逐字二分，indexOfLineAt 仍按行起点二分（行级降级渲染），
     *     但 wordIndexAt 对 isLineLevel 行返回 0（整行当一个"字"），wordProgress 对 isLineLevel
     *     行返回行进度（lineProgress）—— 保证 KaraokeLine/SyncedLine 读 wordIndex 时不越界。
     */
    fun indexOfLineAt(positionMs: Long): Int {
        var lo = 0
        var hi = sorted.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 行结束时间：优先下一行起点，兜底行内最后一个字的 end，再兜底 start+5s */
    fun lineEndMs(index: Int): Long {
        val line = sorted.getOrNull(index) ?: return 0L
        val next = sorted.getOrNull(index + 1)
        if (next != null && next.startMs > line.startMs) return next.startMs
        line.words.lastOrNull()?.let { if (it.endMs > line.startMs) return it.endMs }
        return line.startMs + 5_000L
    }

    /** 当前行整体进度 0..1（行级歌词 SyncedLine 的高亮进度） */
    fun lineProgress(index: Int, positionMs: Long): Float {
        val line = sorted.getOrNull(index) ?: return 0f
        val start = line.startMs
        val end = lineEndMs(index)
        if (positionMs <= start) return 0f
        if (positionMs >= end) return 1f
        val span = (end - start).coerceAtLeast(1L)
        return (positionMs - start).toFloat() / span
    }

    /** 二分：positionMs 落在行内第几个字，words 为空返回 -1 */
    fun wordIndexAt(line: LyricLine, positionMs: Long): Int {
        var lo = 0
        var hi = line.words.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (line.words[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 当前字内进度 0..1（卡拉OK逐字揭示 + 轻微弹跳曲线的输入） */
    fun wordProgress(line: LyricLine, wordIndex: Int, positionMs: Long): Float {
        if (wordIndex < 0 || wordIndex >= line.words.size) return 0f
        val w = line.words[wordIndex]
        if (positionMs <= w.startMs) return 0f
        if (positionMs >= w.endMs) return 1f
        val span = (w.endMs - w.startMs).coerceAtLeast(1L)
        return (positionMs - w.startMs).toFloat() / span
    }

    /** 行内已揭示的字的个数（含正在揭示的字按小数计） */
    fun revealedWordCount(line: LyricLine, positionMs: Long): Float {
        if (line.words.isEmpty()) return 0f
        val idx = wordIndexAt(line, positionMs)
        if (idx < 0) return 0f
        return idx + wordProgress(line, idx, positionMs)
    }

    /** 行的真实结束时间：行内最后一个字的 end；无逐字则用行起点（行级歌词） */
    private fun rawLineEndMs(index: Int): Long {
        val line = sorted.getOrNull(index) ?: return 0L
        line.words.lastOrNull()?.let { if (it.endMs > line.startMs) return it.endMs }
        return line.startMs
    }

    /**
     * positionMs 是否落在歌词空档（前奏 / 长间奏）——「歌词等待」动画的触发条件。
     *   · 前奏：第一行起点在 0.8s 之后，且 pos 还没到第一行
     *   · 间奏：上一行末尾与下一行起点间隔 ≥ 2.5s，且 pos 落在间隔中段
     *     （前后各留余量，避免行刚结束 / 下一行快开始时闪烁）
     */
    fun isInGap(positionMs: Long): Boolean {
        if (sorted.isEmpty()) return false
        val first = sorted[0]
        if (positionMs < first.startMs) {
            return first.startMs > 800L   // 前奏足够长才显示等待
        }
        for (i in 0 until sorted.size - 1) {
            val end = rawLineEndMs(i)
            val nextStart = sorted[i + 1].startMs
            val gap = nextStart - end
            // 真机 Beta13：间奏不足 **4.75 秒**就不显示等待球（太短的停顿不值得插球）
            if (gap >= 4_750L &&
                positionMs >= end + 400L &&
                positionMs < nextStart - 600L
            ) {
                return true
            }
        }
        return false
    }
}
