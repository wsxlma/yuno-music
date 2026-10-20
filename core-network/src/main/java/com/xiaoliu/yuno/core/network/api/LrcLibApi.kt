package com.xiaoliu.yuno.core.network.api

import com.xiaoliu.yuno.core.network.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * LRCLIB 客户端 —— 歌词兜底主源（Apple 私有歌词拿不到时用）。
 *
 * 官方接口：https://lrclib.net/api/get （签名精确匹配）、/api/search （模糊搜索）
 * 要求带能署名的 User-Agent（作者就一个抠脚程序员，署我们自己的名字）。
 */
@Serializable
data class LrcLibRecord(
    val id: Long = 0,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String = "",
    val duration: Double = 0.0,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null
)

class LrcLibApi {

    private val json = Json { ignoreUnknownKeys = true }

    private val headers = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Yuno/1.0 (personal Apple Music client; xaehdb@gmail.com)"
    )

    /** 精确签名查询：歌名+歌手必填，专辑和时长（秒）可选——带上命中率更高 */
    suspend fun get(
        artist: String,
        track: String,
        album: String? = null,
        durationSec: Long? = null
    ): LrcLibRecord? = runCatching {
        val url = buildString {
            append("https://lrclib.net/api/get?artist_name=").append(enc(artist))
            append("&track_name=").append(enc(track))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (durationSec != null && durationSec > 0) append("&duration=").append(durationSec)
        }
        json.decodeFromString<LrcLibRecord>(Http.get(url, headers))
    }.getOrNull()

    /** 模糊搜索：精确签名失败时用，取第一条有 syncedLyrics 的 */
    suspend fun search(query: String): List<LrcLibRecord> = runCatching {
        val url = "https://lrclib.net/api/search?q=" + enc(query)
        json.decodeFromString<List<LrcLibRecord>>(Http.get(url, headers))
    }.getOrDefault(emptyList())

    /** 组合拳：先 /get 再 /search，挑一个好用的 */
    suspend fun findBest(
        artist: String,
        track: String,
        album: String? = null,
        durationSec: Long? = null
    ): LrcLibRecord? {
        get(artist, track, album, durationSec)
            ?.takeIf { !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank() }
            ?.let { return it }
        val results = search("$track $artist")
        return results.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
            ?: results.firstOrNull { !it.plainLyrics.isNullOrBlank() }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
