package com.xiaoliu.yuno.data.repository

import org.json.JSONArray
import org.json.JSONObject
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.AlbumDetail
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.Track

/**
 * 本地磁盘缓存（真机需求：不要每次进 App 都从零加载）。
 *
 * 策略：cache-first —— ViewModel 先读缓存立即上屏（loading=false），
 * 再后台刷新网络结果回写。刷新失败且已有缓存内容时，不打断展示。
 *
 * 只存目录数据（歌名/封面/ID），不存任何令牌；streamUrl 是一次性 lease，不缓存。
 */
class LocalCache(cacheDir: java.io.File) {

    /** 二级缓存根目录（再套一层，避免与其它缓存内容混放） */
    private val dir = java.io.File(cacheDir, "catalog").apply { mkdirs() }

    fun read(key: String): String? = runCatching {
        val f = java.io.File(dir, "$key.json")
        if (f.exists()) f.readText() else null
    }.getOrNull()

    fun write(key: String, content: String) {
        runCatching { java.io.File(dir, "$key.json").writeText(content) }
    }
}

/** 主页货架 ↔ JSON（手写序列化，不引新的序列化依赖） */
object HomeCache {

    private const val KEY_TYPE = "type"
    private const val T_RECENT = "recent"
    private const val T_ALBUMS = "albums"
    private const val T_PLAYLISTS = "playlists"

    fun serialize(sections: List<HomeSection>): String {
        val arr = JSONArray()
        sections.forEach { s ->
            val o = JSONObject()
            o.put("title", s.title)
            when (s) {
                is HomeSection.RecentPlays -> {
                    o.put(KEY_TYPE, T_RECENT)
                    o.put("tracks", JSONArray().apply { s.tracks.forEach { put(trackToJson(it)) } })
                }
                is HomeSection.AlbumsShelf -> {
                    o.put(KEY_TYPE, T_ALBUMS)
                    o.put("albums", JSONArray().apply { s.albums.forEach { put(albumToJson(it)) } })
                }
                is HomeSection.PlaylistsShelf -> {
                    o.put(KEY_TYPE, T_PLAYLISTS)
                    o.put("playlists", JSONArray().apply {
                        s.playlists.forEach { put(playlistToJson(it)) }
                    })
                }
            }
            arr.put(o)
        }
        return arr.toString()
    }

    fun parse(json: String): List<HomeSection> = runCatching {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.optString("title")
                when (o.optString(KEY_TYPE)) {
                    T_RECENT -> {
                        val tracks = jsonArray(o, "tracks") { trackFromJson(it) }
                        if (tracks.isNotEmpty()) add(HomeSection.RecentPlays(title, tracks))
                    }
                    T_ALBUMS -> {
                        val albums = jsonArray(o, "albums") { albumFromJson(it) }
                        if (albums.isNotEmpty()) add(HomeSection.AlbumsShelf(title, albums))
                    }
                    T_PLAYLISTS -> {
                        val playlists = jsonArray(o, "playlists") { playlistFromJson(it) }
                        if (playlists.isNotEmpty()) add(HomeSection.PlaylistsShelf(title, playlists))
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun <T> jsonArray(o: JSONObject, key: String, map: (JSONObject) -> T): List<T> =
        o.optJSONArray(key)?.let { a ->
            buildList { for (i in 0 until a.length()) a.optJSONObject(i)?.let { add(map(it)) } }
        } ?: emptyList()
}

/** 资料库数据 ↔ JSON（歌曲/专辑/歌单/最近添加四组） */
object LibraryCache {

    data class Payload(
        val songs: List<Track>,
        val albums: List<Album>,
        val playlists: List<Playlist>,
        val recentlyAdded: List<Album>
    )

    fun serialize(p: Payload): String {
        val o = JSONObject()
        o.put("songs", JSONArray().apply { p.songs.forEach { put(trackToJson(it)) } })
        o.put("albums", JSONArray().apply { p.albums.forEach { put(albumToJson(it)) } })
        o.put("playlists", JSONArray().apply { p.playlists.forEach { put(playlistToJson(it)) } })
        o.put("recent", JSONArray().apply { p.recentlyAdded.forEach { put(albumToJson(it)) } })
        return o.toString()
    }

    fun parse(json: String): Payload? = runCatching {
        val o = JSONObject(json)
        fun optArr(key: String) = o.optJSONArray(key)

        Payload(
            songs = optArr("songs")?.toObjects()?.map(::trackFromJson) ?: emptyList(),
            albums = optArr("albums")?.toObjects()?.map(::albumFromJson) ?: emptyList(),
            playlists = optArr("playlists")?.toObjects()?.map(::playlistFromJson) ?: emptyList(),
            recentlyAdded = optArr("recent")?.toObjects()?.map(::albumFromJson) ?: emptyList()
        )
    }.getOrNull()

}

/** 专辑/歌单详情 ↔ JSON —— 二级页缓存（真机：退出再进不应重新加载） */
object DetailCache {

    fun serializePlaylist(d: PlaylistDetail): String = JSONObject()
        .put("playlist", playlistToJson(d.playlist))
        .put("tracks", JSONArray().apply { d.tracks.forEach { put(trackToJson(it)) } })
        .toString()

    fun parsePlaylist(json: String): PlaylistDetail? = runCatching {
        val o = JSONObject(json)
        PlaylistDetail(
            playlist = playlistFromJson(o.getJSONObject("playlist")),
            tracks = o.optJSONArray("tracks")?.toObjects()?.map(::trackFromJson) ?: emptyList()
        )
    }.getOrNull()

    fun serializeAlbum(d: AlbumDetail): String = JSONObject()
        .put("album", albumToJson(d.album))
        .put("tracks", JSONArray().apply { d.tracks.forEach { put(trackToJson(it)) } })
        .toString()

    fun parseAlbum(json: String): AlbumDetail? = runCatching {
        val o = JSONObject(json)
        AlbumDetail(
            album = albumFromJson(o.getJSONObject("album")),
            tracks = o.optJSONArray("tracks")?.toObjects()?.map(::trackFromJson) ?: emptyList()
        )
    }.getOrNull()
}

/** JSONArray → JSONObject 列表（各 Cache 共用） */
private fun JSONArray.toObjects(): List<JSONObject> = buildList {
    for (i in 0 until length()) {
        optJSONObject(i)?.let { add(it) }
    }
}

// ---------------- 模型 ↔ JSON 基础转换 ----------------

internal fun trackToJson(t: Track): JSONObject = JSONObject()
    .put("id", t.id)
    .put("title", t.title)
    .put("artist", t.artist)
    .put("album", t.album)
    .put("artworkUrl", t.artworkUrl)
    .put("durationMs", t.durationMs)

internal fun trackFromJson(o: JSONObject): Track = Track(
    id = o.optString("id"),
    title = o.optString("title"),
    artist = o.optString("artist"),
    album = o.optString("album"),
    artworkUrl = o.optString("artworkUrl"),
    durationMs = o.optLong("durationMs")
)

internal fun albumToJson(a: Album): JSONObject = JSONObject()
    .put("id", a.id)
    .put("title", a.title)
    .put("artist", a.artist)
    .put("artistId", a.artistId)
    .put("artworkUrl", a.artworkUrl)
    .put("releaseDate", a.releaseDate)
    .put("trackCount", a.trackCount)
    .put("genreName", a.genreName)
    .put("url", a.url)

internal fun albumFromJson(o: JSONObject): Album = Album(
    id = o.optString("id"),
    title = o.optString("title"),
    artist = o.optString("artist"),
    artistId = o.optString("artistId"),
    artworkUrl = o.optString("artworkUrl"),
    releaseDate = o.optString("releaseDate"),
    trackCount = o.optInt("trackCount"),
    genreName = o.optString("genreName"),
    url = o.optString("url")
)

internal fun playlistToJson(p: Playlist): JSONObject = JSONObject()
    .put("id", p.id)
    .put("name", p.name)
    .put("curator", p.curator)
    .put("artworkUrl", p.artworkUrl)
    .put("trackCount", p.trackCount)

internal fun playlistFromJson(o: JSONObject): Playlist = Playlist(
    id = o.optString("id"),
    name = o.optString("name"),
    curator = o.optString("curator"),
    artworkUrl = o.optString("artworkUrl"),
    trackCount = o.optInt("trackCount")
)