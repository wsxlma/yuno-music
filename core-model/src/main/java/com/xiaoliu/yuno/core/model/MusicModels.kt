package com.xiaoliu.yuno.core.model

/**
 * 曲目 —— 全项目统一的播放单元（也是 ExoPlayer 队列元素）。
 *
 * streamUrl 允许为空字符串：Apple Music 的播放地址是一次性 lease（播放租约），
 * 真正点播前由 data 层的流解析器现场换取后回填，UI 拿到的目录数据里没有它。
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val durationMs: Long,
    val streamUrl: String = ""
)

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val artistId: String = "",
    val artworkUrl: String = "",
    val releaseDate: String = "",
    val trackCount: Int = 0,
    val genreName: String = "",
    val editorialNotes: String = "",
    val url: String = ""
)

data class Artist(
    val id: String,
    val name: String,
    val artworkUrl: String = "",
    val genre: String = ""
)

data class Playlist(
    val id: String,
    val name: String,
    val curator: String = "",
    val artworkUrl: String = "",
    val trackCount: Int = 0
)

/** 主页分区：最近播放 / 为你推荐专辑 / 推荐歌单（对应 Apple Music「主页」的货架） */
sealed interface HomeSection {
    val title: String

    data class RecentPlays(
        override val title: String,
        val tracks: List<Track>
    ) : HomeSection

    data class AlbumsShelf(
        override val title: String,
        val albums: List<Album>
    ) : HomeSection

    data class PlaylistsShelf(
        override val title: String,
        val playlists: List<Playlist>
    ) : HomeSection
}

/** 搜索聚合结果（歌曲 / 专辑 / 歌手三个 Tab 共用一份数据） */
data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList()
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/** Apple Music 订阅状态（登录后展示在资料库头部） */
data class SubscriptionInfo(
    val active: Boolean = false,
    val tier: String = ""
)

/**
 * 歌曲榜（charts 端点）。
 *
 * Apple 的 charts 只有三种 kind —— mostPlayed / dailyGlobalTop / cityTop，
 * 没有「月度」维度；其中只有 mostPlayed 按 storefront 出，各地区 Top 25 就是它。
 * [name] 原样保留 Apple 返回的榜单名，不自己改写。
 */
data class SongChart(
    val name: String,
    val storefront: String,
    val tracks: List<Track>
)

/** 专辑详情 = 专辑元数据 + 曲目列表 */
data class AlbumDetail(
    val album: Album,
    val tracks: List<Track>
)

/** 歌单详情 = 歌单元数据 + 曲目列表 */
data class PlaylistDetail(
    val playlist: Playlist,
    val tracks: List<Track>
)
