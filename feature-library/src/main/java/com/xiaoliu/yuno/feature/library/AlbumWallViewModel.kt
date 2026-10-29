package com.xiaoliu.yuno.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.PlaylistDetail
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.DetailCache
import com.xiaoliu.yuno.data.repository.LibraryCache
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LocalCache
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 墙上的一张卡（2026-10-29 2.8.0b1）。
 *
 * 专辑和歌单混在同一面墙上，所以用一个密封接口归一化，
 * 但要保留「这是专辑还是歌单」的信息 —— 因为**点播放球的去向不同**：
 *   专辑 → 直接播整张；歌单 → 进三排胶卷详情墙（不播）。
 */
sealed interface WallItem {
    val key: String
    val title: String
    val subtitle: String
    val artworkUrl: String

    data class AlbumTile(val album: Album) : WallItem {
        override val key: String get() = "a:${album.id}"
        override val title: String get() = album.title
        override val subtitle: String get() = album.artist
        override val artworkUrl: String get() = album.artworkUrl
    }

    data class PlaylistTile(val playlist: Playlist) : WallItem {
        override val key: String get() = "p:${playlist.id}"
        override val title: String get() = playlist.name
        override val subtitle: String
            get() = playlist.curator.ifBlank {
                if (playlist.trackCount > 0) "${playlist.trackCount} 首" else "播放列表"
            }
        override val artworkUrl: String get() = playlist.artworkUrl
    }
}

data class AlbumWallUiState(
    val loading: Boolean = true,
    val items: List<WallItem> = emptyList(),
    /** 当前选中卡的 key；null = 未选中 */
    val selectedKey: String? = null,
    val error: String? = null,
    /** 第二屏（歌单三排墙）的数据；null = 未打开 */
    val openPlaylist: PlaylistDetail? = null,
    val playlistLoading: Boolean = false,
    val playlistError: String? = null
) {
    /** 第一张歌单卡在 items 里的下标 —— 主墙初始定位要用它把歌单摆到页面中间 */
    val firstPlaylistIndex: Int
        get() = items.indexOfFirst { it is WallItem.PlaylistTile }
}

/**
 * 专辑墙唯一的数据源（2026-10-29 2.8.0b1-r）。
 *
 * 一个 VM 同时服务两屏：主墙（专辑 + 歌单混排）与歌单三排详情墙。
 * 数据全部走已存在的 LibraryRepository；播放全部转给 PlaybackController。
 */
class AlbumWallViewModel(
    private val library: LibraryRepository,
    private val cache: LocalCache,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(AlbumWallUiState())
    val state: StateFlow<AlbumWallUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * cache-first：先拿磁盘上的旧墙立刻上屏，网络回来再原地刷新。
     * 两个接口各自容错 —— 歌单接口挂了不该让专辑也从墙上消失。
     */
    fun load() {
        viewModelScope.launch {
            val cached = runCatching { cache.read(CACHE_KEY) }.getOrNull()
                ?.let { runCatching { LibraryCache.parse(it) }.getOrNull() }
            if (cached != null && (cached.albums.isNotEmpty() || cached.playlists.isNotEmpty())) {
                _state.update {
                    it.copy(loading = false, items = buildItems(cached.albums, cached.playlists))
                }
            } else {
                _state.update { it.copy(loading = true) }
            }

            kotlinx.coroutines.coroutineScope {
                val albumsD = async { runCatching { library.libraryAlbums() } }
                val playlistsD = async { runCatching { library.libraryPlaylists() } }
                val albumsR = albumsD.await()
                val playlistsR = playlistsD.await()

                val albums = albumsR.getOrDefault(emptyList())
                val playlists = playlistsR.getOrDefault(emptyList())

                if (albums.isNotEmpty() || playlists.isNotEmpty()) {
                    cache.write(
                        CACHE_KEY,
                        LibraryCache.serialize(
                            LibraryCache.Payload(
                                songs = emptyList(),
                                albums = albums,
                                playlists = playlists,
                                recentlyAdded = emptyList()
                            )
                        )
                    )
                    _state.update {
                        it.copy(
                            loading = false,
                            items = buildItems(albums, playlists),
                            error = null
                        )
                    }
                } else {
                    val msg = albumsR.exceptionOrNull()?.message
                        ?: playlistsR.exceptionOrNull()?.message
                        ?: "资料库加载失败"
                    _state.update { prev ->
                        prev.copy(
                            loading = false,
                            // 缓存兜底有内容时静默保留，下次重进再试
                            error = if (prev.items.isEmpty()) msg else null
                        )
                    }
                }
            }
        }
    }

    /**
     * 墙面顺序：**歌单卡放在序列正中**。
     *
     * 用户验收要求「播放列表的磁贴必须放在页面中心位，不用找」——
     * 把歌单夹在专辑中间，主墙初始定位到序列中点，歌单自然就在第一屏正中央。
     */
    private fun buildItems(albums: List<Album>, playlists: List<Playlist>): List<WallItem> {
        val half = albums.size / 2
        val head = albums.take(half).map { WallItem.AlbumTile(it) }
        val mid = playlists.map { WallItem.PlaylistTile(it) }
        val tail = albums.drop(half).map { WallItem.AlbumTile(it) }
        return head + mid + tail
    }

    fun select(key: String?) {
        _state.update { it.copy(selectedKey = if (it.selectedKey == key) null else key) }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedKey = null) }
    }

    // ---------------- 主墙：专辑直接播 ----------------

    fun playAlbum(album: Album) {
        viewModelScope.launch {
            val tracks = runCatching { library.albumTracks(album.id) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) controller.playQueue(tracks, 0)
        }
    }

    // ---------------- 第二屏：歌单三排墙 ----------------

    /** 打开歌单详情墙（只加载曲目，不播放 —— 用户钦定） */
    fun openPlaylist(playlist: Playlist) {
        _state.update { it.copy(playlistLoading = true, playlistError = null) }
        viewModelScope.launch {
            val key = "wall_pl_${playlist.id}"
            val cached = runCatching { cache.read(key) }.getOrNull()
                ?.let { runCatching { DetailCache.parsePlaylist(it) }.getOrNull() }
            if (cached != null) {
                _state.update { it.copy(openPlaylist = cached, playlistLoading = false) }
            }
            val detail = runCatching { library.playlistDetail(playlist.id) }.getOrNull()
            if (detail != null) {
                runCatching { cache.write(key, DetailCache.serializePlaylist(detail)) }
                _state.update { it.copy(openPlaylist = detail, playlistLoading = false) }
            } else if (cached == null) {
                _state.update {
                    it.copy(
                        playlistLoading = false,
                        playlistError = "歌单加载失败",
                        // 网络失败也把壳开出来，退出球可用
                        openPlaylist = PlaylistDetail(playlist = playlist, tracks = emptyList())
                    )
                }
            }
        }
    }

    fun closePlaylist() {
        _state.update { it.copy(openPlaylist = null, playlistError = null) }
    }

    /** 选中卡片的播放球：从该首开始播整个歌单 */
    fun playPlaylistFrom(trackIndex: Int) {
        val tracks = _state.value.openPlaylist?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            controller.playQueue(tracks, trackIndex.coerceIn(0, tracks.lastIndex))
        }
    }

    /** 播放全部：从头播 + 清选中 */
    fun playPlaylistAll() {
        val tracks = _state.value.openPlaylist?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        clearSelection()
        viewModelScope.launch { controller.playQueue(tracks, 0) }
    }

    /** 随机播放：乱序从头播 + 清选中 */
    fun playPlaylistShuffle() {
        val tracks = _state.value.openPlaylist?.tracks.orEmpty()
        if (tracks.isEmpty()) return
        clearSelection()
        val shuffled = tracks.shuffled()
        viewModelScope.launch { controller.playQueue(shuffled, 0) }
    }

    private companion object {
        const val CACHE_KEY = "albumwall"
    }
}
