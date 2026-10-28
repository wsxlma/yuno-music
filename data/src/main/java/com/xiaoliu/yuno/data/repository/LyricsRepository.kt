package com.xiaoliu.yuno.data.repository

import android.util.LruCache
import com.xiaoliu.yuno.core.model.LyricLine
import com.xiaoliu.yuno.core.model.Lyrics
import com.xiaoliu.yuno.core.model.LyricsSource
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.model.Word
import com.xiaoliu.yuno.core.network.LyricsSourcePref
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.core.network.api.AppleLyricsApi
import com.xiaoliu.yuno.core.network.api.NetEaseLyricsApi
import com.xiaoliu.yuno.core.network.lyrics.NetEaseYrcParser
import com.xiaoliu.yuno.core.network.api.LrcLibApi
import com.xiaoliu.yuno.core.network.lyrics.LyricsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 歌词仓库 —— 三级缓存 + 两路数据源。
 *
 * 取词顺序（需求钦定）：
 *   内存缓存 → 文件缓存 → Apple 私有歌词（TTML，逐字）→ LRCLIB（LRC 行级）→ 无歌词
 *
 * 缓存 key = songId；文件缓存存原始文本（TTML/LRC），解析每次现做（解析很便宜，
 * 存原文还有个好处：解析器升级后老缓存直接受益）。
 */
class LyricsRepository(
    private val appleLyrics: AppleLyricsApi,
    private val lrcLib: LrcLibApi,
    private val cacheDir: File
) {

    /** key = songId */
    private val memory = LruCache<String, Lyrics>(48)

    suspend fun getLyrics(track: Track): Lyrics? = withContext(Dispatchers.IO) {
        val mode = LyricsSourcePref.mode
        // 内存缓存：模式过滤（切"仅 LRCLIB"后不采纳旧 Apple 缓存）
        memory.get(track.id)?.let { if (LyricsSourcePref.allows(it.source)) return@withContext it }

        // 文件缓存：同源过滤 + 时间轴校验（真机根因：某些歌缓存里的时间轴就是坏的）
        readCache(track.id, track.durationMs)?.takeIf {
            it.validTimeline && LyricsSourcePref.allows(it.source)
        }?.let { cached ->
            memory.put(track.id, cached)
            return@withContext cached
        }

        // ---- 1) Apple 私有歌词（TTML；逐字 + 背景人声）----
        // 用户选了"仅 LRCLIB"时整段跳过
        if (mode != LyricsSourcePref.Mode.LRCLIB_ONLY) {
            val ttml = runCatching { appleLyrics.fetchTtml(track.id) }.getOrNull()
            if (!ttml.isNullOrBlank()) {
                val lines = LyricsParser.parseTtml(ttml)
                if (!lines.isNullOrEmpty()) {
                    val lyrics = Lyrics(track.id, lines.withPhraseSpacing(), LyricsSource.APPLE_TTML)
                    if (isValidTimeline(lyrics.lines, track.durationMs)) {
                        writeCache(track.id, "ttml", ttml)
                        memory.put(track.id, lyrics)
                        return@withContext lyrics
                    }
                    YunoLog.d(
                        "Lyrics",
                        "Apple TTML timeline invalid for ${track.id} " +
                            "(reason=${timelineFaultReason(lyrics.lines, track.durationMs)})"
                    )
                    // 时间轴坏 → 清掉坏缓存，走 LRCLIB 换源重取
                    deleteBadCache(track.id, "ttml")
                }
            }
        }

// ---- 2) LRCLIB 兜底（syncedLyrics 是行级 LRC；增强 LRC 语法也兼容）----
        // ★ 2026-10-28 2.6.4（用户钦定"逐字优先"）：
        //   AUTO 模式下，LRCLIB 拿到的是**行级**歌词时不立刻返回，先挂着，
        //   继续去网易云碰运气拿 YRC（逐字）；网易云也拿不到逐字才回落到这份行级。
        //   起因：原顺序是 Apple → LRCLIB → 网易云，而 LRCLIB 大多是行级、
        //   网易云的 YRC 才是逐字 —— 等于"逐字源排在行级源后面"，绝大多数歌
        //   在 LRCLIB 就命中返回，永远拿不到逐字（用户反馈"字亮起来的时机不对"）。
        var heldLineLevel: Lyrics? = null
        var heldLineLevelRaw: String? = null
        // 用户选了"仅 Apple"时整段跳过
        if (mode != LyricsSourcePref.Mode.APPLE_ONLY) {
            val durationSec = (track.durationMs / 1000L).takeIf { it > 0 }
            val record = runCatching {
                lrcLib.findBest(
                    artist = track.artist,
                    track = track.title,
                    album = track.album.takeIf { it.isNotBlank() },
                    durationSec = durationSec
                )
            }.getOrNull()

            val synced = record?.syncedLyrics
            if (!synced.isNullOrBlank()) {
                val parsed = LyricsParser.parseLrc(synced)
                if (parsed != null && parsed.lines.isNotEmpty()) {
                    val source =
                        if (parsed.wordLevel) LyricsSource.LRCLIB_ENHANCED
                        else LyricsSource.LRCLIB_LRC
                    val lyrics = Lyrics(track.id, parsed.lines.withPhraseSpacing(), source)
                    if (isValidTimeline(lyrics.lines, track.durationMs)) {
                        if (parsed.wordLevel || mode != LyricsSourcePref.Mode.AUTO) {
                            writeCache(track.id, "lrc", synced)
                            memory.put(track.id, lyrics)
                            return@withContext lyrics
                        }
                        // AUTO + 行级：先挂着，去试网易云逐字（**不写缓存**，
                        // 否则下次直接命中行级，永远不会升级到逐字）
                        heldLineLevel = lyrics
                        heldLineLevelRaw = synced
                    } else {
                        YunoLog.d(
                            "Lyrics",
                            "LRCLIB timeline invalid for ${track.id} " +
                                "(reason=${timelineFaultReason(lyrics.lines, track.durationMs)})"
                        )
                        deleteBadCache(track.id, "lrc")
                    }
                }
            }
        }

        // ---- 3) 网易云兜底（官方 Web API：先按"歌名+歌手"搜换 ID，再取词）----
        // Apple 的 adamId ≠ 网易云 ID；宁缺毋滥，搜不到/时长对不上就放弃
        if (mode != LyricsSourcePref.Mode.APPLE_ONLY) {
            val netease = runCatching {
                val nid = NetEaseLyricsApi.searchSongId(track.title, track.artist, track.durationMs)
                nid?.let { NetEaseLyricsApi.getLyricText(it) }
            }.getOrNull()
            if (!netease.isNullOrBlank()) {
                // yrc 是 JSON 行格式（{"t":..,"c":[..]}），其余是 LRC —— 按首字符分派
                val lines = if (netease.trimStart().startsWith("{")) {
                    NetEaseYrcParser.parse(netease)
                } else {
                    LyricsParser.parseLrc(netease)?.lines
                }
                if (!lines.isNullOrEmpty()) {
                    // YRC（逐字）才会被这里优先采纳；行级 LRC 只在没有 LRCLIB 兜底时才用
                    val isWordLevel = netease.trimStart().startsWith("{")
                    val lyrics = Lyrics(track.id, lines.withPhraseSpacing(), LyricsSource.NETEASE)
                    if (isValidTimeline(lyrics.lines, track.durationMs)) {
                        if (isWordLevel || heldLineLevel == null) {
                            writeCache(track.id, "netease", netease)
                            memory.put(track.id, lyrics)
                            return@withContext lyrics
                        }
                        // 网易云也是行级、但手上已有 LRCLIB 行级 → 保留 LRCLIB（更早的来源优先）
                    } else {
                        YunoLog.d(
                            "Lyrics",
                            "NetEase timeline invalid for ${track.id} " +
                                "(reason=${timelineFaultReason(lyrics.lines, track.durationMs)})"
                        )
                        deleteBadCache(track.id, "netease")
                    }
                }
            }
        }

        // ---- 3) 回落到之前挂起的 LRCLIB 行级（AUTO 模式下没有逐字源时的最终答案）----
        heldLineLevel?.let { lv ->
            heldLineLevelRaw?.let { writeCache(track.id, "lrc", it) }
            memory.put(track.id, lv)
            return@withContext lv
        }

        // ---- 4) 真没有 / 各源时间轴都坏 ----
        null
    }

    private fun readCache(songId: String, durationMs: Long = 0L): Lyrics? {
        val ttmlFile = File(cacheDir, "$songId.ttml")
        if (ttmlFile.isFile) {
            val lines = LyricsParser.parseTtml(ttmlFile.readText())
            if (!lines.isNullOrEmpty()) {
                val lyrics = Lyrics(
                    songId,
                    lines.withPhraseSpacing(),
                    LyricsSource.APPLE_TTML,
                    validTimeline = isValidTimeline(lines, durationMs)
                )
                if (lyrics.validTimeline) return lyrics
                deleteBadCache(songId, "ttml")
            }
        }
        val lrcFile = File(cacheDir, "$songId.lrc")
        if (lrcFile.isFile) {
            val parsed = LyricsParser.parseLrc(lrcFile.readText())
            if (parsed != null && parsed.lines.isNotEmpty()) {
                val source =
                    if (parsed.wordLevel) LyricsSource.LRCLIB_ENHANCED
                    else LyricsSource.LRCLIB_LRC
                val lyrics = Lyrics(
                    songId,
                    parsed.lines.withPhraseSpacing(),
                    source,
                    validTimeline = isValidTimeline(parsed.lines, durationMs)
                )
                if (lyrics.validTimeline) return lyrics
                deleteBadCache(songId, "lrc")
            }
        }
        // 网易云缓存（yrc JSON 行 / LRC，按首字符分派）
        val neFile = File(cacheDir, "$songId.netease")
        if (neFile.isFile) {
            val text = neFile.readText()
            val lines = if (text.trimStart().startsWith("{")) {
                NetEaseYrcParser.parse(text)
            } else {
                LyricsParser.parseLrc(text)?.lines
            }
            if (!lines.isNullOrEmpty()) {
                val lyrics = Lyrics(
                    songId,
                    lines.withPhraseSpacing(),
                    LyricsSource.NETEASE,
                    validTimeline = isValidTimeline(lines, durationMs)
                )
                if (lyrics.validTimeline) return lyrics
                deleteBadCache(songId, "netease")
            }
        }
        return null
    }

    private fun writeCache(songId: String, ext: String, content: String) {
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$songId.$ext").writeText(content)
        }
    }

    /** 时间轴坏了（真机根因：某些歌数据错 → 看着"亮最后一句"）→ 删掉坏缓存，避免下次还命中 */
    private fun deleteBadCache(songId: String, ext: String) {
        runCatching { File(cacheDir, "$songId.$ext").delete() }
    }
}

// ============================================================================
// 时间轴校验（真机根因：某些歌的歌词时间轴数据本身错，
// 比如相邻行时间戳倒挂、超过歌曲总时长、全 0。
// 校验失败 → 自动换源重取（Apple TTML 坏 → 走 LRCLIB），两源都坏 → 无歌词。
// ============================================================================

/**
 * 判定时间轴是否健康。返回 true = 正常可渲染；false = 坏，应换源。
 *
 * 坏掉的典型形态（任一命中即判坏）：
 *   · 相邻两行 startMs 倒挂（后一行比前一行早，中间跳 < -500ms）
 *   · 任意一行 startMs 超过歌曲总时长×1.1（给 10% 余量，万不可用 /10——
 *     1.0.67 就是把 110% 错写成 1/10，导致 24 秒后的歌词全被判坏删除）
 *   · 全部行 startMs 都是 0（未对齐 / 无时间戳）
 *   · 行数为 0
 */
private fun isValidTimeline(lines: List<LyricLine>, durationMs: Long): Boolean {
    if (lines.isEmpty()) return false
    // 全 0 行（无时间戳对齐）
    if (lines.all { it.startMs == 0L }) return false
    // 超过总时长 1.1 倍的行（durationMs <= 0 表示未知总时长，跳过此检查）
    if (durationMs > 0) {
        val limit = (durationMs * 11L) / 10L
        if (lines.any { it.startMs > limit }) return false
    }
    // 相邻行倒挂（中间跳 < -500ms 视为坏）
    for (i in 1 until lines.size) {
        val gap = lines[i].startMs - lines[i - 1].startMs
        if (gap < -500L) return false
    }
    return true
}

/** 坏时间轴的成因描述（日志用，方便真机诊断哪首歌哪类数据错） */
private fun timelineFaultReason(lines: List<LyricLine>, durationMs: Long): String {
    if (lines.isEmpty()) return "empty"
    if (lines.all { it.startMs == 0L }) return "all_zero"
    if (durationMs > 0) {
        val limit = (durationMs * 11L) / 10L
        if (lines.any { it.startMs > limit }) return "over_duration"
    }
    for (i in 1 until lines.size) {
        if (lines[i].startMs - lines[i - 1].startMs < -500L) return "inverted"
    }
    return "unknown"
}

// ============================================================================
// 断句空格（真机讨论：Apple 官方版在断句处会有一个空格，我们的歌词数据没有）
//
// 不手敲字 —— 用逐字歌词自带的时间轴推断断句：
//   · 相邻两个词之间的停顿 ≥ 300ms → 视为断句，在第二个词前补一个空格
//   · 前一词以中文标点结尾 → 同样补空格
//   · 只处理"中文 → 中文"的邻接，英文/数字本来就有空格，不重复插
//
// 空格是直接加进 Word.text 的，所以 plainText、逐字揭示的字符偏移、
// 竖屏/横屏/浮动歌词三套渲染全部自动一致。
// ============================================================================

private const val PHRASE_GAP_MS = 300L

private const val PHRASE_PUNCT = "，。！？、；：…,.!?;:"

private fun List<LyricLine>.withPhraseSpacing(): List<LyricLine> = map { line ->
    val words = line.words
    if (words.size < 2) return@map line

    val out = ArrayList<Word>(words.size)
    words.forEachIndexed { i, w ->
        if (i == 0) {
            out += w
            return@forEachIndexed
        }
        val prev = out.last()
        // 已经有空格/换行的地方不重复插
        if (prev.text.endsWith(" ") || w.text.startsWith(" ")) {
            out += w
            return@forEachIndexed
        }
        val gap = w.startMs - prev.endMs
        val timeBreak = gap >= PHRASE_GAP_MS
        val punctBreak = prev.text.isNotEmpty() && prev.text.last() in PHRASE_PUNCT
        val prevCjk = prev.text.lastOrNull()?.let(::isCjk) == true
        val nextCjk = w.text.firstOrNull()?.let(::isCjk) == true

        out += if ((timeBreak || punctBreak) && prevCjk && nextCjk) {
            w.copy(text = " " + w.text)
        } else {
            w
        }
    }
    line.copy(words = out)
}

private fun isCjk(ch: Char): Boolean =
    ch.code in 0x4E00..0x9FFF ||     // 基本汉字
        ch.code in 0x3400..0x4DBF || // 扩展 A
        ch.code in 0xF900..0xFAFF || // 兼容汉字
        ch.code in 0x3000..0x303F    // 中文标点
