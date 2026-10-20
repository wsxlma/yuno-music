package com.xiaoliu.yuno.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.SubscriptionInfo
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.data.repository.LibraryCache
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LocalCache
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LibraryFilter(val label: String) {
    ALL("全部"),
    SONGS("歌曲"),
    ALBUMS("专辑"),
    PLAYLISTS("播放列表"),
    FAVORITES("本地收藏")
}

data class LibraryUiState(
    val loading: Boolean = true,
    val songs: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val recentlyAdded: List<Album> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val subscription: SubscriptionInfo = SubscriptionInfo(),
    val accountName: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val error: String? = null
) {
    val favoriteSongs: List<Track>
        get() = songs.filter { it.id in favorites }
}

class LibraryViewModel(
    private val library: LibraryRepository,
    private val cache: LocalCache,
    private val controller: PlaybackController
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    init {
        load()
        // 本地收藏（DataStore）实时回流
        viewModelScope.launch {
            library.favoriteIds.collect { ids ->
                _state.update { it.copy(favorites = ids) }
            }
        }
    }

    /**
     * 逐接口容错：六个接口并发、各自独立成败。
     * 只有「歌曲/专辑/歌单」全军覆没才算加载失败——
     * 某个接口 400（比如最近添加）绝不会再把整个资料库拖黑。
     *
     * cache-first（真机需求：不要每次进 App 都从零加载）：
     * 磁盘缓存先上屏，网络结果后台刷新回写；刷新失败且缓存里有内容时不打断展示。
     */
    fun load() {
        viewModelScope.launch {
            val cached = runCatching { cache.read("library") }.getOrNull()
                ?.let { runCatching { LibraryCache.parse(it) }.getOrNull() }
            if (cached != null &&
                (cached.songs.isNotEmpty() || cached.albums.isNotEmpty() || cached.playlists.isNotEmpty())
            ) {
                _state.update {
                    it.copy(
                        loading = false,
                        songs = cached.songs,
                        albums = cached.albums,
                        playlists = cached.playlists,
                        recentlyAdded = cached.recentlyAdded
                    )
                }
            } else {
                _state.update { it.copy(loading = true) }
            }
            _state.update { it.copy(error = null) }

            kotlinx.coroutines.coroutineScope {
                val songsD = async { runCatching { library.librarySongs() } }
                val albumsD = async { runCatching { library.libraryAlbums() } }
                val playlistsD = async { runCatching { library.libraryPlaylists() } }
                val recentD = async { runCatching { library.recentlyAddedAlbums() } }
                val subD = async { runCatching { library.subscription() } }
                val nameD = async { runCatching { library.accountName() } }

                val songsR = songsD.await()
                val albumsR = albumsD.await()
                val playlistsR = playlistsD.await()

                val songs = songsR.getOrDefault(emptyList())
                val albums = albumsR.getOrDefault(emptyList())
                val playlists = playlistsR.getOrDefault(emptyList())
                val allEmpty = songs.isEmpty() && albums.isEmpty() && playlists.isEmpty()
                val firstError = songsR.exceptionOrNull()?.message
                    ?: albumsR.exceptionOrNull()?.message
                    ?: playlistsR.exceptionOrNull()?.message

                val recent = recentD.await().getOrDefault(emptyList())

                // 刷新成功拿到内容 → 回写缓存
                if (!allEmpty) {
                    cache.write(
                        "library",
                        LibraryCache.serialize(
                            LibraryCache.Payload(songs, albums, playlists, recent)
                        )
                    )
                }

                _state.update { prev ->
                    prev.copy(
                        loading = false,
                        songs = songs,
                        albums = albums,
                        playlists = playlists,
                        recentlyAdded = recent,
                        subscription = subD.await().getOrDefault(SubscriptionInfo()),
                        accountName = nameD.await().getOrNull().orEmpty().ifBlank { prev.accountName },
                        // 三组全空才报错；缓存兜底有内容时静默保留（下次重进再试）
                        error = if (allEmpty && prev.songs.isEmpty() && prev.albums.isEmpty() &&
                            prev.playlists.isEmpty()
                        ) (firstError ?: "资料库加载失败") else null
                    )
                }
            }
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun playSong(track: Track) {
        viewModelScope.launch {
            val queue = _state.value.songs.ifEmpty { listOf(track) }
            val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.playQueue(queue, index)
        }
    }

    fun playAlbum(album: Album) {
        viewModelScope.launch {
            val tracks = library.albumTracks(album.id).ifEmpty { return@launch }
            controller.playQueue(tracks, 0)
        }
    }

    fun toggleFavorite(trackId: String) {
        viewModelScope.launch {
            library.toggleFavorite(trackId)
        }
    }

    /** 加入待播列表（行长按菜单；官方 addMediaItem 路径，主线程转发） */
    fun addToQueue(track: Track) = controller.addToQueue(track)

    /** 插播到下一首 */
    fun playNext(track: Track) = controller.playNext(track)
}
