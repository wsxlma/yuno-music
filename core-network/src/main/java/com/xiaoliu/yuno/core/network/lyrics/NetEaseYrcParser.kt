package com.xiaoliu.yuno.core.network.lyrics

import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.Word
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 网易云 yrc 逐字歌词解析器。
 *
 * yrc 是**每行一个 JSON 对象**的格式（真机文档实证）：
 * ```
 * {"t":0,"c":[{"tx":"作词: "},{"tx":"STELLA"}]}
 * {"t":10315,"c":[{"tx":"[00:10.315]作曲: "},{"tx":"STELLA"}]}
 * ```
 *   · t = 该行起始时间（毫秒）
 *   · c = 文本块数组，tx = 块文本
 *   · 基础版 c 无逐块时间戳（tb/te）——没有真逐字时间就不编造，
 *     整行按行级处理（isLineLevel=true，渲染走整行渐亮，诚实降级）
 *
 * 注意：文本里可能内嵌 LRC 风格前缀（如 "[00:10.315]"），原样保留不影响显示，
 * 只在计算纯文本长度时无影响（tx 原样拼接）。
 */
object NetEaseYrcParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class YrcLine(val t: Long? = null, val c: List<YrcChunk>? = null)

    @Serializable
    private data class YrcChunk(val tx: String? = null)

    /**
     * 解析 yrc JSON 行格式 → 行级 LyricLine 列表。
     * @return 解析失败或空返回 null
     */
    fun parse(text: String): List<LyricLine>? {
        if (text.isBlank()) return null
        val out = mutableListOf<YrcLine>()
        for (raw in text.lines()) {
            val s = raw.trim()
            if (!s.startsWith("{")) continue
            val parsed = runCatching { json.decodeFromString<YrcLine>(s) }.getOrNull()
            if (parsed != null && parsed.t != null) out += parsed
        }
        if (out.isEmpty()) return null

        val sorted = out.sortedBy { it.t }
        val lines = ArrayList<LyricLine>(sorted.size)
        for ((i, yl) in sorted.withIndex()) {
            val start = yl.t ?: continue
            val txt = yl.c?.joinToString("") { it.tx.orEmpty() }.orEmpty().trim()
            if (txt.isEmpty()) continue
            val nextStart = sorted.getOrNull(i + 1)?.t
            val end = when {
                nextStart != null && nextStart > start -> nextStart
                else -> start + 4000L
            }
            lines += LyricLine(
                startMs = start,
                words = listOf(Word(txt, start, end)),
                isLineLevel = true
            )
        }
        return lines.ifEmpty { null }
    }
}
