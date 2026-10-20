package com.xiaoliu.yuno.core.network.api

import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.AlbumDetail
import com.xiaoliu.yuno.core.model.Artist
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.SearchResults
import com.xiaoliu.yuno.core.model.SongChart
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.network.Http
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/** me/account?meta=subscription 的提炼结果 */
data class AppleAccountInfo(
    val subscriptionActive: Boolean,
    val storefront: String?
)

/**
 * Apple Music 官方 API（api.music.apple.com）客户端。
 *
 * 头约定（调研确证）：
 *  - 目录类（catalog）：Authorization: Bearer <dev token>
 *  - 用户类（me）：再加 Music-User-Token: <media user token>
 *  - storefront 不走 header，走 URL 路径
 *
 * 响应结构随 Apple 版本会漂移，这里统一"防御式挖 JSON"：
 * 字段缺失返回默认值，绝不让一个 null 炸掉整页 UI。
 */
class AppleMusicApi(
    private val auth: AuthStore,
    private val tokenProvider: DevTokenProvider
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun storefront(): String = auth.cached.storefront.ifBlank { "us" }

    private suspend fun headers(useUserToken: Boolean): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        tokenProvider.developerToken()?.let { map["Authorization"] = "Bearer $it" }
        if (useUserToken) {
            val userToken = auth.cached.mediaUserToken
            if (userToken.isNotBlank()) map["Music-User-Token"] = userToken
        }
        // ★ 关键：网页播放器令牌绑定了 root_https_origin=["apple.com"]，
        //   不带 Origin 头全部 401（本机矩阵实测：同一 token 无 Origin=401 / 带 Origin=200）。
        //   2026-10-18 真机 401 排障确认。
        map["Origin"] = "https://music.apple.com"
        return map
    }

    // ================= 目录：搜索 =================

    suspend fun search(term: String, limit: Int = 12): SearchResults {
        val url = "https://api.music.apple.com/v1/catalog/${storefront()}/search" +
            "?term=${enc(term)}&types=songs,albums,artists&limit=$limit"
        val results = parse(Http.get(url, headers(useUserToken = false)))?.obj("results")
            ?: return SearchResults()
        return SearchResults(
            tracks = results.obj("songs")?.arr("data").toObjects().mapNotNull(::resourceToTrack),
            albums = results.obj("albums")?.arr("data").toObjects().mapNotNull(::resourceToAlbum),
            artists = results.obj("artists")?.arr("data").toObjects().mapNotNull(::resourceToArtist)
        )
    }

    // ================= 目录：专辑 / 榜单 / 单曲 =================

    suspend fun albumDetail(albumId: String): AlbumDetail? {
        val url = "https://api.music.apple.com/v1/catalog/${storefront()}/albums/$albumId?include=tracks"
        val resource = parse(Http.get(url, headers(useUserToken = false)))
            ?.arr("data").toObjects().firstOrNull()
            ?: return null
        val album = resourceToAlbum(resource) ?: return null
        val tracks = resource.obj("relationships")
            ?.obj("tracks")
            ?.arr("data").toObjects()
            .mapNotNull(::resourceToTrack)
        return AlbumDetail(album, tracks)
    }

    /** 热门专辑榜（首页"热门"货架） */
    suspend fun chartsAlbums(limit: Int = 12): List<Album> {
        val url = "https://api.music.apple.com/v1/catalog/${storefront()}/charts?types=albums&limit=$limit"
        val groups = parse(Http.get(url, headers(useUserToken = false)))
            ?.obj("results")?.arr("albums") ?: return emptyList()
        return groups.toObjects()                    // results.albums = [ {chart, data:[...]} ]
            .flatMap { group -> group.arr("data").toObjects() }
            .mapNotNull(::resourceToAlbum)
    }

    /**
     * 歌曲榜（Top 25）。
     *
     * charts 端点的 kind 只有三种 —— mostPlayed / dailyGlobalTop / cityTop，
     * 没有「月度」维度；其中只有 mostPlayed 按 storefront 出，各地区 Top 25 就是它。
     * 这里不传 chart 参数取默认榜（该地区的 Top Songs），榜单名原样回传给 UI，
     * Apple 叫什么就显示什么，不自己编。
     *
     * @param storefront 地区码（us / cn / jp…）；null = 账号地区
     */
    suspend fun chartSongs(limit: Int = 25, storefront: String? = null): SongChart {
        val sf = storefront?.trim()?.ifBlank { null } ?: storefront()
        val url = "https://api.music.apple.com/v1/catalog/$sf/charts?types=songs&limit=$limit"
        // results.songs = [ {chart, name, data:[...]} ]
        val groups = parse(Http.get(url, headers(useUserToken = false)))
            ?.obj("results")?.arr("songs").toObjects()
        // 取第一个有内容的榜；榜单名跟着一起走，UI 侧不硬编码标题
        val group = groups.firstOrNull { it.arr("data").toObjects().isNotEmpty() }
        return SongChart(
            name = group.str("name").orEmpty(),
            storefront = sf,
            tracks = group.arr("data").toObjects().mapNotNull(::resourceToTrack)
        )
    }

    /** 单曲的 30 秒试听地址（未加密 m4a，播放兜底方案） */
    suspend fun songPreviewUrl(songId: String): String? {
        val url = "https://api.music.apple.com/v1/catalog/${storefront()}/songs/$songId"
        val attrs = parse(Http.get(url, headers(useUserToken = false)))
            ?.arr("data").toObjects().firstOrNull()?.obj("attributes")
        return attrs?.arr("previews").toObjects().firstOrNull()?.str("url")
    }

    // ================= 用户：最近播放 / 推荐 =================

    /** 最近播放的歌曲（首页「继续播放」用这个，不是 recent/played） */
    suspend fun recentlyPlayedTracks(limit: Int = 12): List<Track> {
        val url = "https://api.music.apple.com/v1/me/recent/played/tracks?limit=$limit"
        return parse(Http.get(url, headers(useUserToken = true)))
            ?.arr("data").toObjects()
            .mapNotNull(::resourceToTrack)
    }

    /**
     * 个性化推荐（官方 REST 存在）。
     * 返回结构随 Apple 版本漂移，这里深挖 type=albums / playlists 的资源并去重。
     */
    suspend fun recommendations(): Pair<List<Album>, List<Playlist>> {
        val data = parse(Http.get("https://api.music.apple.com/v1/me/recommendations", headers(true)))
            ?.arr("data").toObjects()
            ?: return emptyList<Album>() to emptyList()

        val albums = mutableListOf<Album>()
        val playlists = mutableListOf<Playlist>()

        fun dig(node: kotlinx.serialization.json.JsonElement, depth: Int) {
            if (depth > 6) return
            when (node) {
                is JsonArray -> node.forEach { dig(it, depth + 1) }
                is JsonObject -> {
                    when (node.str("type")) {
                        "albums" -> if (node["attributes"] != null) {
                            resourceToAlbum(node)?.let { albums += it }
                        }
                        "playlists" -> if (node["attributes"] != null) {
                            resourceToPlaylist(node)?.let { playlists += it }
                        }
                    }
                    node.values.forEach { dig(it, depth + 1) }
                }
                else -> Unit
            }
        }
        data.forEach { dig(it, 0) }
        return albums.distinctBy { it.id }.take(12) to playlists.distinctBy { it.id }.take(12)
    }

    // ================= 用户：资料库 =================

    /** 资料库歌曲（自动翻页，最多 5 页；翻页失败就停在当前页，不拖垮整个请求链） */
    suspend fun librarySongs(maxItems: Int = 300): List<Track> {
        val out = mutableListOf<Track>()
        var url: String? = "https://api.music.apple.com/v1/me/library/songs?limit=100&include=catalog"
        var pages = 0
        while (url != null && out.size < maxItems && pages < 5) {
            // Apple 的 library 大库翻页有已知坑（offset 翻页可能直接 400）——
            // 第二页挂了也必须带着第一页的数据回来，绝不整个资料库拖黑（真机 400 排障结论）
            val body = runCatching { Http.get(url, headers(useUserToken = true)) }.getOrNull()
                ?: break
            val root = parse(body) ?: break
            root.arr("data").toObjects().forEach { res ->
                resourceToTrack(res)?.let { out += it }
            }
            val next = root.str("next")
            url = if (next.isNullOrBlank()) null else "https://api.music.apple.com$next"
            pages++
        }
        return out.distinctBy { it.id }
    }

    suspend fun libraryAlbums(maxItems: Int = 300): List<Album> {
        val out = mutableListOf<Album>()
        var url: String? = "https://api.music.apple.com/v1/me/library/albums?limit=100&include=catalog"
        var pages = 0
        while (url != null && out.size < maxItems && pages < 5) {
            val body = runCatching { Http.get(url, headers(useUserToken = true)) }.getOrNull()
                ?: break
            val root = parse(body) ?: break
            root.arr("data").toObjects().forEach { res ->
                resourceToAlbum(res)?.let { out += it }
            }
            val next = root.str("next")
            url = if (next.isNullOrBlank()) null else "https://api.music.apple.com$next"
            pages++
        }
        return out.distinctBy { it.id }
    }

    suspend fun libraryPlaylists(limit: Int = 100): List<Playlist> {
        val url = "https://api.music.apple.com/v1/me/library/playlists?limit=$limit"
        return parse(Http.get(url, headers(useUserToken = true)))
            ?.arr("data").toObjects()
            .mapNotNull(::resourceToPlaylist)
            .distinctBy { it.id }
    }

    /**
     * 最近添加（混合资源，只要专辑；include=catalog 换真 catalog id）。
     * 注意：该接口 limit 上限 25（日志实证："Value must be an integer less than or equal to 25"），
     * 传 30 会 400。
     */
    suspend fun recentlyAddedAlbums(limit: Int = 25): List<Album> {
        val url = "https://api.music.apple.com/v1/me/library/recently-added?limit=$limit&include=catalog"
        return parse(Http.get(url, headers(useUserToken = true)))
            ?.arr("data").toObjects()
            .filter { it.str("type") == "albums" }
            .mapNotNull(::resourceToAlbum)
    }

    /** 资料库歌单详情（含曲目；include=tracks）—— 点开资料库歌单用 */
    suspend fun libraryPlaylistDetail(playlistId: String): PlaylistDetail? {
        val url = "https://api.music.apple.com/v1/me/library/playlists/$playlistId?include=tracks"
        val res = parse(Http.get(url, headers(useUserToken = true)))
            ?.arr("data").toObjects().firstOrNull() ?: return null
        val playlist = resourceToPlaylist(res) ?: return null
        val tracks = res.obj("relationships")?.obj("tracks")?.arr("data").toObjects()
            .mapNotNull(::resourceToTrack)
        return PlaylistDetail(playlist, tracks)
    }

    /** 加入资料库（写操作） */
    suspend fun addAlbumToAppleLibrary(albumId: String): Boolean = runCatching {
        Http.postJson(
            "https://api.music.apple.com/v1/me/library?ids[albums]=$albumId",
            "{}",
            headers(useUserToken = true)
        )
        true
    }.getOrDefault(false)

    // ================= 账号 =================

    suspend fun accountInfo(): AppleAccountInfo? {
        val url = "https://api.music.apple.com/v1/me/account?meta=subscription"
        val root = parse(Http.get(url, headers(useUserToken = true))) ?: return null
        val sub = root.obj("meta")?.obj("subscription")
        return AppleAccountInfo(
            subscriptionActive = sub?.bool("active") ?: false,
            storefront = sub?.str("storefront")
        )
    }

    // ================= JSON 防御工具 =================

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonArray?.toObjects(): List<JsonObject> =
        this?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.long(key: String): Long? =
        (this?.get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun artworkUrl(attrs: JsonObject?, size: Int = 632): String =
        attrs.obj("artwork").str("url").orEmpty()
            .replace("{w}", size.toString())
            .replace("{h}", size.toString())

    // ================= 资源映射 =================

    private fun resourceToTrack(res: JsonObject): Track? {
        // 资料库资源：优先用 catalog relationship（include=catalog 返回的 catalog 版本，
        // 带真正的数字 catalog id —— 没有它，播放 lease 和 catalog 详情都会失败）
        val catalogRes = res.obj("relationships")?.obj("catalog")?.arr("data").toObjects().firstOrNull()
        val attrs = catalogRes?.obj("attributes") ?: res.obj("attributes") ?: return null
        val playParams = attrs.obj("playParams")
        val catalogId = catalogRes?.str("id")
            ?: playParams.str("catalogId")
            ?: playParams.str("id")
            ?: res.str("id")
            ?: return null
        return Track(
            id = catalogId,
            title = attrs.str("name").orEmpty(),
            artist = attrs.str("artistName").orEmpty(),
            album = attrs.str("albumName").orEmpty(),
            artworkUrl = artworkUrl(attrs),
            durationMs = attrs.long("durationInMillis") ?: 0L
        )
    }

    private fun resourceToAlbum(res: JsonObject): Album? {
        // 同上：资料库专辑的 playParams.id 是 "l.*" 资料库 id，拿去查 catalog 详情会 404，
        // 必须走 include=catalog 的 relationship 换真 catalog id
        val catalogRes = res.obj("relationships")?.obj("catalog")?.arr("data").toObjects().firstOrNull()
        val attrs = catalogRes?.obj("attributes") ?: res.obj("attributes") ?: return null
        val id = catalogRes?.str("id")
            ?: attrs.obj("playParams")?.str("catalogId")
            ?: attrs.obj("playParams")?.str("id")
            ?: res.str("id")
            ?: return null
        return Album(
            id = id,
            title = attrs.str("name").orEmpty(),
            artist = attrs.str("artistName").orEmpty(),
            artworkUrl = artworkUrl(attrs),
            releaseDate = attrs.str("releaseDate").orEmpty(),
            trackCount = attrs.int("trackCount") ?: 0,
            genreName = attrs.arr("genreNames")?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty(),
            editorialNotes = attrs.obj("editorialNotes")?.str("short").orEmpty(),
            url = attrs.str("url").orEmpty()
        )
    }

    private fun resourceToArtist(res: JsonObject): Artist? {
        val attrs = res.obj("attributes") ?: return null
        val name = attrs.str("name") ?: return null
        return Artist(
            id = res.str("id").orEmpty(),
            name = name,
            artworkUrl = artworkUrl(attrs, 400),
            genre = attrs.arr("genreNames")?.firstOrNull()?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    private fun resourceToPlaylist(res: JsonObject): Playlist? {
        val attrs = res.obj("attributes") ?: return null
        val name = attrs.str("name") ?: return null
        return Playlist(
            id = res.str("id").orEmpty(),
            name = name,
            curator = attrs.str("curatorName").orEmpty(),
            artworkUrl = artworkUrl(attrs),
            trackCount = attrs.int("trackCount") ?: 0
        )
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
