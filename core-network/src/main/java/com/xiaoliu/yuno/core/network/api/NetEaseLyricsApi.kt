package com.xiaoliu.yuno.core.network.api

import com.xiaoliu.yuno.core.network.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * 网易云音乐歌词源 —— 官方 Web API 双接口（真机验证 2026-10-20）：
 *   1) 搜索：GET music.163.com/api/search/get/web?s={歌名 歌手}&type=1&limit=5
 *      （Apple 的 adamId ≠ 网易云 ID，必须先按"歌名+歌手"换 ID）
 *   2) 歌词：GET music.163.com/api/song/lyric?id={neteaseId}&lv=1&kv=1&tv=-1
 *      响应含 lrc(行级) / yrc(逐字,部分歌有) / tlyric(翻译) / klyric(卡拉OK)
 *
 * 注意：必须带 UA + Referer: https://music.163.com/，否则 403。
 * 不猜测任何第三方镜像域名（此前试的 api.loyalsoldier.net 已不存在）。
 */
object NetEaseLyricsApi {

    private val json = Json { ignoreUnknownKeys = true }
    private const val UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val HEADERS = mapOf(
        "User-Agent" to UA,
        "Referer" to "https://music.163.com/",
        "Accept" to "application/json"
    )

    /**
     * 按歌名+歌手搜网易云歌曲 → 选最匹配的一首的 ID。
     * 打分：标题完全相等 > 标题包含 > 歌手包含 > 时长接近（±5s）。
     * 拿不到合适结果返回 null（宁缺毋滥——错歌的歌词比没歌词更糟）。
     */
    suspend fun searchSongId(title: String, artist: String, durationMs: Long): String? =
        withContext(Dispatchers.IO) {
            val kw = URLEncoder.encode("$title $artist", "UTF-8")
            val url = "https://music.163.com/api/search/get/web?s=$kw&type=1&limit=5"
            val resp = runCatching { Http.get(url, HEADERS) }.getOrNull() ?: return@withContext null

            val root = runCatching {
                json.decodeFromString<SearchResponse>(resp)
            }.getOrNull() ?: return@withContext null

            val songs = root.result?.songs.orEmpty()
            if (songs.isEmpty()) return@withContext null

            val cleanTitle = title.trim()
            val cleanArtist = artist.trim()
            var bestId: String? = null
            var bestScore = 0
            for (s in songs) {
                val name = s.name.orEmpty()
                val firstArtist = s.artists?.firstOrNull()?.name.orEmpty()
                var score = 0
                if (name == cleanTitle) score += 100
                else if (name.contains(cleanTitle) || cleanTitle.contains(name)) score += 60
                if (firstArtist.contains(cleanArtist) || cleanArtist.contains(firstArtist)) score += 40
                // 时长校验（网易云 duration 单位就是毫秒）
                if (durationMs > 0 && s.duration != null &&
                    Math.abs(s.duration - durationMs) < 5_000L
                ) score += 30
                if (score > bestScore) {
                    bestScore = score
                    bestId = s.id
                }
            }
            // 门槛：至少标题或歌手对得上，防止把完全无关的歌的歌词拿回来
            if (bestScore >= 40) bestId?.takeIf { it.isNotBlank() } else null
        }

    /**
     * 取歌词正文（优先逐字 yrc，其次行级 lrc），返回原始文本。
     * 无歌词/接口失败返回 null。
     */
    suspend fun getLyricText(songId: String): String? = withContext(Dispatchers.IO) {
        val url = "https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
        val resp = runCatching { Http.get(url, HEADERS) }.getOrNull() ?: return@withContext null
        val root = runCatching {
            json.decodeFromString<LyricResponse>(resp)
        }.getOrNull() ?: return@withContext null
        root.yrc?.lyric?.takeIf { it.isNotBlank() }
            ?: root.lrc?.lyric?.takeIf { it.isNotBlank() }
    }

    // ===== Response Models（只建用到的字段，其余 ignoreUnknownKeys） =====

    @Serializable
    data class SearchResponse(val result: SearchResult? = null)

    @Serializable
    data class SearchResult(val songs: List<Song>? = null)

    @Serializable
    data class Song(
        val id: String? = null,
        val name: String? = null,
        val duration: Long? = null,
        val artists: List<Artist>? = null
    )

    @Serializable
    data class Artist(val name: String? = null)

    @Serializable
    data class LyricResponse(
        val lrc: LyricBlock? = null,
        val yrc: LyricBlock? = null
    )

    @Serializable
    data class LyricBlock(val lyric: String? = null)
}
