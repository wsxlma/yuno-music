package com.xiaoliu.yuno.core.model

/**
 * 逐字歌词的一个字/词，时间戳相对歌曲起点，单位毫秒。
 * （Apple Music TTML 的 syllable span / 增强 LRC 的 <mm:ss.xx> 词都解析成这个）
 */
data class Word(
    val text: String,
    val startMs: Long,
    val endMs: Long
)

/**
 * 歌词行。
 *
 * 渲染分支约定：
 *  - words 非空 且 isLineLevel = false → 逐字卡拉OK（KaraokeLine，Apple Music 风格）
 *  - isLineLevel = true → 行级歌词（SyncedLine，整行渐变高亮），
 *    此时 words 只有一个覆盖整行的 Word（文本就是行内容），
 *    这样用户给定的数据模型不需要额外 text 字段就能承载行级歌词。
 *
 * isBackground → 背景人声（Apple TTML 的 x-bg / agent="background"），渲染时整体降透明度。
 */
data class LyricLine(
    val startMs: Long,
    val words: List<Word> = emptyList(),
    val isLineLevel: Boolean = false,
    val agent: String? = null,
    val isBackground: Boolean = false
) {
    val plainText: String get() = words.joinToString(separator = "") { it.text }
    val hasWords: Boolean get() = words.isNotEmpty()
    /** 行真实结束时间：行内最后一个字的 end（行级歌词兜底 start+5s） */
    val endMs: Long get() = words.lastOrNull()?.let { if (it.endMs > startMs) it.endMs else null } ?: (startMs + 5_000L)
}

/** 一首歌的完整歌词 + 元信息 */
data class Lyrics(
    val songId: String,
    val lines: List<LyricLine>,
    val source: LyricsSource,
    /** 时间轴健康标记（入库前校验过：相邻行不倒挂、不超时长、不全 0） */
    val validTimeline: Boolean = true
)

enum class LyricsSource { APPLE_TTML, LRCLIB_ENHANCED, LRCLIB_LRC, NETEASE, NONE }
