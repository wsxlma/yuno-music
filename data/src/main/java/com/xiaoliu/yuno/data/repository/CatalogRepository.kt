package com.xiaoliu.yuno.data.repository

import com.xiaoliu.yuno.core.model.AlbumDetail
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.SearchResults
import com.xiaoliu.yuno.core.network.api.AppleMusicApi

/**
 * 目录仓库 —— 搜索 / 专辑详情 / 主页货架组装。
 * 主页 = 最近播放 + 个性化推荐 + 热门榜，任何一段失败都跳过，全空才报错。
 */
class CatalogRepository(
    private val api: AppleMusicApi
) {

    suspend fun homeSections(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()

        // 1) 继续播放（最近播放的歌曲）
        runCatching { api.recentlyPlayedTracks(limit = 12) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { sections += HomeSection.RecentPlays("继续播放", it) }

        // 2) 个性化推荐（官方 me/recommendations）
        runCatching { api.recommendations() }.getOrNull()?.let { (albums, playlists) ->
            if (albums.isNotEmpty()) {
                sections += HomeSection.AlbumsShelf("为你推荐", albums)
            }
            if (playlists.isNotEmpty()) {
                sections += HomeSection.PlaylistsShelf("为你精选的歌单", playlists)
            }
        }

        // 3) 热门专辑榜
        runCatching { api.chartsAlbums(limit = 12) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { sections += HomeSection.AlbumsShelf("热门专辑", it) }

        if (sections.isEmpty()) {
            throw IllegalStateException("主页数据为空 —— 检查登录状态与网络")
        }
        return sections
    }

    suspend fun search(term: String): SearchResults = api.search(term)

    suspend fun albumDetail(albumId: String): AlbumDetail? = api.albumDetail(albumId)
}
