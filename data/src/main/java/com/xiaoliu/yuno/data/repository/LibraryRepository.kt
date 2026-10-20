package com.xiaoliu.yuno.data.repository

import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.SubscriptionInfo
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.network.api.AppleMusicApi
import com.xiaoliu.yuno.core.network.auth.AuthStore
import kotlinx.coroutines.flow.Flow

/**
 * 资料库仓库 —— Apple 账号云端资料库 + 本地收藏。
 */
class LibraryRepository(
    private val api: AppleMusicApi,
    private val favorites: FavoriteStore,
    private val auth: AuthStore
) {

    val favoriteIds: Flow<Set<String>> = favorites.ids

    suspend fun toggleFavorite(trackId: String) = favorites.toggle(trackId)

    suspend fun librarySongs(): List<Track> = api.librarySongs()

    suspend fun libraryAlbums(): List<Album> = api.libraryAlbums()

    suspend fun libraryPlaylists(): List<Playlist> = api.libraryPlaylists()

    /** 最近添加（limit 25 是接口上限，日志实证） */
    suspend fun recentlyAddedAlbums(): List<Album> = api.recentlyAddedAlbums(limit = 25)

    /** 点资料库专辑 → 拉曲目整张播放（资料库专辑的 id 已在映射时换成 catalogId） */
    suspend fun albumTracks(albumId: String): List<Track> =
        api.albumDetail(albumId)?.tracks.orEmpty()

    /** 歌单详情（点开资料库歌单） */
    suspend fun playlistDetail(playlistId: String): PlaylistDetail? =
        api.libraryPlaylistDetail(playlistId)

    suspend fun addAlbumToAppleLibrary(albumId: String): Boolean =
        api.addAlbumToAppleLibrary(albumId)

    /** 订阅状态；顺带把权威 storefront 写回凭据（比 cookie 里的 itua 更可靠） */
    suspend fun subscription(): SubscriptionInfo {
        val info = runCatching { api.accountInfo() }.getOrNull() ?: return SubscriptionInfo()
        info.storefront?.takeIf { it.isNotBlank() }?.let { sf ->
            runCatching { auth.patch(storefront = sf.lowercase()) }
        }
        return SubscriptionInfo(active = info.subscriptionActive)
    }

    suspend fun accountName(): String = auth.cached.accountName
}
