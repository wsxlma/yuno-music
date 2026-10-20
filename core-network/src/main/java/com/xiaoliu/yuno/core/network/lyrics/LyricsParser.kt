package com.xiaoliu.yuno.core.network.lyrics

import android.util.Xml
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.Word
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** 解析产物：歌词行 + 是否逐字（true 走卡拉OK渲染，false 走行级渲染） */
data class ParsedLyrics(
    val lines: List<LyricLine>,
    val wordLevel: Boolean
)

/**
 * 歌词解析器 —— TTML（Apple Music 私有歌词） + LRC / 增强 LRC（LRCLIB 兜底）。
 *
 * TTML 规范要点（Apple 的 <tt> 文档）：
 *  - 每行一个 <p begin end>，字级 <span begin end>，可能嵌套（摊平处理）
 *  - 时间格式 "00:00:01.234"；也有 "1234ms" 的毫秒梗，统一走 parseTimeMs
 *  - 背景人声：属性 role="x-bg"（或 agent 含 background）
 *  - 命名空间若干（ttml: itunes:），解析时忽略命名空间按 localName 匹配
 */
object LyricsParser {

    // ============================ TTML ============================

    fun parseTtml(xmlText: String): List<LyricLine>? = runCatching {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xmlText))

        val lines = mutableListOf<LyricLine>()

        var inLine = false
        var lineStart = 0L
        var lineEnd = 0L
        var lineAgent: String? = null
        var lineBackground = false
        val linePlain = StringBuilder()
        val lineWords = mutableListOf<Word>()

        var inSpan = false
        var spanStart = 0L
        var spanEnd = 0L
        val spanText = StringBuilder()

        fun resetSpan() {
            inSpan = false
            spanText.clear()
        }

        fun resetLine() {
            inLine = false
            linePlain.clear()
            lineWords.clear()
            lineAgent = null
            lineBackground = false
            lineStart = 0L
            lineEnd = 0L
        }

        fun finishSpan() {
            val text = spanText.toString()
            if (text.isNotEmpty()) {
                val end = if (spanEnd > spanStart) spanEnd else spanStart + 300L
                lineWords += Word(text, spanStart, end)
            }
            resetSpan()
        }

        fun finishLine() {
            if (!inLine) return
            val bg = lineBackground || lineAgent?.contains("background", ignoreCase = true) == true
            val line = when {
                lineWords.isNotEmpty() -> LyricLine(
                    startMs = lineStart,
                    words = lineWords.toList(),
                    isLineLevel = false,
                    agent = lineAgent,
                    isBackground = bg
                )

                linePlain.isNotBlank() -> {
                    val text = linePlain.toString().trim()
                    val end = if (lineEnd > lineStart) lineEnd else lineStart + 3000L
                    LyricLine(
                        startMs = lineStart,
                        words = listOf(Word(text, lineStart, end)),
                        isLineLevel = true,
                        agent = lineAgent,
                        isBackground = bg
                    )
                }

                else -> null
            }
            if (line != null) lines += line
            resetLine()
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.localName()) {
                    "p" -> {
                        resetLine()
                        inLine = true
                        lineStart = parseTimeMs(parser.attrLocal("begin")) ?: 0L
                        lineEnd = parseTimeMs(parser.attrLocal("end")) ?: 0L
                        lineAgent = parser.attrLocal("agent")
                        lineBackground = parser.isBackgroundTag()
                    }

                    "span" -> if (inLine) {
                        // 嵌套 span 直接摊平：新 span 开始时把上一个欠账收掉
                        if (inSpan) finishSpan()
                        inSpan = true
                        spanStart = parseTimeMs(parser.attrLocal("begin")) ?: lineStart
                        spanEnd = parseTimeMs(parser.attrLocal("end")) ?: 0L
                        if (parser.isBackgroundTag()) lineBackground = true
                        val spanAgent = parser.attrLocal("agent")
                        if (spanAgent != null && lineAgent == null) lineAgent = spanAgent
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (inSpan) spanText.append(text)
                    else if (inLine) linePlain.append(text)
                }

                XmlPullParser.END_TAG -> when (parser.localName()) {
                    "span" -> if (inSpan) finishSpan()
                    "p" -> finishLine()
                }
            }
            event = parser.next()
        }

        normalize(lines)
        lines.ifEmpty { null }
    }.getOrNull()

    // ============================ LRC / 增强 LRC ============================

    private val LINE_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /**
     * 解析 LRC 文本。
     *  - 行级：`[00:12.34]歌词`
     *  - 增强（逐字）：`[00:12.34]<00:12.34>终<00:12.79>于`
     * 自动识别；wordLevel 表示整首歌是否按逐字渲染。
     */
    fun parseLrc(text: String): ParsedLyrics? {
        data class Pending(
            val startMs: Long,
            val words: List<Word>,
            val lineText: String,
            val enhanced: Boolean
        )

        val pending = mutableListOf<Pending>()
        var anyEnhanced = false

        for (raw in text.lines()) {
            val lineTags = LINE_TAG.findAll(raw).toList()
            if (lineTags.isEmpty()) continue          // [ar:] [ti:] 等元数据行天然跳过
            val starts = lineTags.map { m ->
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                mm * 60_000 + ss * 1000 + frac
            }
            val content = raw.substring(lineTags.last().range.last + 1)
            val wordTags = WORD_TAG.findAll(content).toList()

            if (wordTags.isEmpty()) {
                val lineText = content.trim()
                if (lineText.isEmpty()) continue
                starts.forEach { pending += Pending(it, emptyList(), lineText, false) }
            } else {
                anyEnhanced = true
                val raws = mutableListOf<Pair<Long, String>>()   // (startMs, text)
                wordTags.forEachIndexed { i, m ->
                    val wStart = m.groupValues[1].toLong() * 60_000 +
                        m.groupValues[2].toLong() * 1000 +
                        when (m.groupValues[3].length) {
                            0 -> 0L
                            1 -> m.groupValues[3].toLong() * 100
                            2 -> m.groupValues[3].toLong() * 10
                            else -> m.groupValues[3].take(3).toLong()
                        }
                    val textStart = m.range.last + 1
                    val textEnd = if (i + 1 < wordTags.size) wordTags[i + 1].range.first else content.length
                    val wText = content.substring(textStart, textEnd)
                    if (wText.isNotEmpty()) raws += wStart to wText
                }
                if (raws.isEmpty()) continue
                val words = raws.mapIndexed { i, (st, txt) ->
                    Word(txt, st, if (i + 1 < raws.size) raws[i + 1].first else st + 300L)
                }
                // 一行多个行级 tag（副歌复用）：把词表整体平移到各起点
                val base = raws.first().first
                starts.forEach { start ->
                    val shift = start - base
                    pending += Pending(
                        startMs = start,
                        words = if (shift == 0L) words
                        else words.map { Word(it.text, it.startMs + shift, it.endMs + shift) },
                        lineText = "",
                        enhanced = true
                    )
                }
            }
        }

        if (pending.isEmpty()) return null

        val sorted = pending.sortedBy { it.startMs }
        val lines = ArrayList<LyricLine>(sorted.size)
        for ((i, p) in sorted.withIndex()) {
            val nextStart = sorted.getOrNull(i + 1)?.startMs ?: (p.startMs + 6000L)
            if (p.enhanced && p.words.isNotEmpty()) {
                // 逐字：最后一个字若 end 不合法，用下一行起点兜底
                val fixed = p.words.mapIndexed { wi, w ->
                    if (w.endMs <= w.startMs && wi == p.words.lastIndex) {
                        Word(w.text, w.startMs, maxOf(nextStart, w.startMs + 300L))
                    } else w
                }
                lines += LyricLine(p.startMs, fixed, isLineLevel = false)
            } else {
                // 行级：单个 Word 覆盖整行，isLineLevel = true
                val end = maxOf(nextStart, p.startMs + 500L)
                lines += LyricLine(
                    p.startMs,
                    listOf(Word(p.lineText, p.startMs, end)),
                    isLineLevel = true
                )
            }
        }

        return if (lines.isEmpty()) null else ParsedLyrics(lines, anyEnhanced)
    }

    // ============================ 后处理 ============================

    /** 排序 + 修补时间戳：单调递增、end 缺失回填、行级 end 对齐下一行 */
    private fun normalize(lines: MutableList<LyricLine>) {
        val sorted = lines.sortedBy { it.startMs }.toMutableList()
        for ((i, line) in sorted.withIndex()) {
            val nextStart = sorted.getOrNull(i + 1)?.startMs
            val words = line.words.mapIndexed { wi, w ->
                val isLast = wi == line.words.lastIndex
                var end = w.endMs
                if (end <= w.startMs) {
                    end = when {
                        wi < line.words.lastIndex -> line.words[wi + 1].startMs
                        nextStart != null -> nextStart
                        else -> w.startMs + 300L
                    }
                }
                if (line.isLineLevel && nextStart != null && nextStart > w.startMs) {
                    end = nextStart
                }
                Word(w.text, w.startMs, maxOf(end, w.startMs + 80L))
            }
            sorted[i] = line.copy(words = words)
        }
        lines.clear()
        lines.addAll(sorted)
    }

    // ============================ 小工具 ============================

    private fun XmlPullParser.localName(): String =
        (name ?: "").substringAfterLast(':')

    private fun XmlPullParser.attrLocal(target: String): String? {
        for (i in 0 until attributeCount) {
            val n = (getAttributeName(i) ?: "").substringAfterLast(':')
            if (n.equals(target, ignoreCase = true)) return getAttributeValue(i)
        }
        return null
    }

    private fun XmlPullParser.isBackgroundTag(): Boolean {
        val role = attrLocal("role") ?: ""
        val cls = attrLocal("class") ?: ""
        val agent = attrLocal("agent") ?: ""
        return role.contains("x-bg", ignoreCase = true) ||
            cls.contains("background", ignoreCase = true) ||
            agent.contains("background", ignoreCase = true)
    }

    /**
     * 时间字符串 → 毫秒。
     * 支持 "00:00:01.234"（时:分:秒）、"01:02.345"（分:秒）、"12.34"（秒）、"1234ms"、"1.2s"
     */
    fun parseTimeMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.endsWith("ms", ignoreCase = true)) {
            return s.dropLast(2).trim().toDoubleOrNull()?.toLong()
        }
        val body = if (s.endsWith("s", ignoreCase = true)) s.dropLast(1) else s
        val parts = body.split(':')
        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toLong()
                    val m = parts[1].toLong()
                    val sec = parts[2].toDouble()
                    ((h * 3600 + m * 60) * 1000L) + (sec * 1000.0).toLong()
                }

                2 -> {
                    val m = parts[0].toLong()
                    val sec = parts[1].toDouble()
                    (m * 60 * 1000L) + (sec * 1000.0).toLong()
                }

                1 -> (parts[0].toDouble() * 1000.0).toLong()
                else -> null
            }
        } catch (_: NumberFormatException) {
            null
        }
    }
}
