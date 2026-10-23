package com.xiaoliu.yuno.feature.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoliu.yuno.core.model.Album
import com.xiaoliu.yuno.core.model.HomeSection
import com.xiaoliu.yuno.core.model.Lyrics
import com.xiaoliu.yuno.core.model.PlaybackState
import com.xiaoliu.yuno.core.model.Playlist
import com.xiaoliu.yuno.core.model.Track
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.core.playback.PlaybackStateHub
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.feature.tv.login.LanTokenServer
import com.xiaoliu.yuno.data.repository.CatalogRepository
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LyricsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** TV 首页状态（不缓存：电视永远在线，拉不到就报错给重试） */
data class TvHomeUiState(
    val loading: Boolean = true,
    val sections: List<HomeSection> = emptyList(),
    val error: String? = null
)

/** TV 资料库状态 */
data class TvLibraryUiState(
    val loading: Boolean = true,
    val albums: List<Album> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val error: String? = null
)

/**
 * TV 版唯一的 ViewModel —— 播放窄流 + 歌词 + 首页/资料库数据 + 遥控动作转发。
 * 不持有任何播放状态（PlaybackStateHub 是唯一事实源），只拆分与转发。
 */
class TvViewModel(
    private val catalog: CatalogRepository,
    private val library: LibraryRepository,
    private val lyricsRepository: LyricsRepository,
    private val controller: PlaybackController,
    private val authStore: AuthStore,
    private val devTokenProvider: DevTokenProvider
) : ViewModel() {

    val playback: StateFlow<PlaybackState> = PlaybackStateHub.state

    // ---------------- 窄流（谁需要谁订阅） ----------------

    val trackFlow: Flow<Track?> = playback
        .map { it.currentTrack }
        .distinctUntilChanged()

    val positionMsFlow: Flow<Long> = playback
        .map { it.positionMs }
        .distinctUntilChanged()

    val isPlayingFlow: Flow<Boolean> = playback
        .map { it.isPlaying }
        .distinctUntilChanged()

    // ---------------- 首页 ----------------

    private val _home = MutableStateFlow(TvHomeUiState())
    val home: StateFlow<TvHomeUiState> = _home.asStateFlow()

    init {
        loadHome()
        loadLibrary()
    }

    fun loadHome() {
        viewModelScope.launch {
            _home.value = TvHomeUiState(loading = true)
            val result = try {
                Result.success(catalog.homeSections())
            } catch (e: Exception) {
                Result.failure(e)
            }
            _home.value = result.fold(
                onSuccess = { TvHomeUiState(loading = false, sections = it) },
                onFailure = { e ->
                    TvHomeUiState(
                        loading = false,
                        sections = _home.value.sections,
                        error = e.message ?: "首页加载失败"
                    )
                }
            )
        }
    }

    // ---------------- 资料库 ----------------

    private val _libraryUi = MutableStateFlow(TvLibraryUiState())
    val libraryUi: StateFlow<TvLibraryUiState> = _libraryUi.asStateFlow()

    fun loadLibrary() {
        viewModelScope.launch {
            _libraryUi.value = TvLibraryUiState(loading = true)
            val albums = try {
                library.libraryAlbums()
            } catch (e: Exception) {
                emptyList()
            }
            val playlists = try {
                library.libraryPlaylists()
            } catch (e: Exception) {
                emptyList()
            }
            val failed = albums.isEmpty() && playlists.isEmpty()
            _libraryUi.value = TvLibraryUiState(
                loading = false,
                albums = albums,
                playlists = playlists,
                error = if (failed) "资料库加载失败" else null
            )
        }
    }

    // ---------------- 歌词 ----------------

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private var lyricsJob: Job? = null

    private fun loadLyrics(track: Track) {
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            _lyricsLoading.value = true
            _lyrics.value = null
            // 内存缓存 → 文件缓存 → Apple TTML → LRCLIB(增强/LRC) → null
            _lyrics.value = lyricsRepository.getLyrics(track)
            _lyricsLoading.value = false
        }
    }

    init {
        viewModelScope.launch {
            trackFlow.collect { track ->
                if (track == null) {
                    _lyrics.value = null
                    _lyricsLoading.value = false
                } else {
                    loadLyrics(track)
                }
            }
        }
    }

    // ---------------- 局域网登录 ----------------

    sealed interface LanState {
        data object Idle : LanState
        data class Waiting(val url: String) : LanState
        data class Error(val message: String) : LanState
        data object Done : LanState
    }

    private val _lan = MutableStateFlow<LanState>(LanState.Idle)
    val lan: StateFlow<LanState> = _lan.asStateFlow()

    private var lanServer: LanTokenServer? = null

    /** 开启局域网登录端口（手机浏览器 → MusicKit 授权 / 手动粘贴 → token 回传） */
    fun startLanLogin() {
        if (lanServer != null) return
        var server: LanTokenServer? = null
        server = LanTokenServer(
            devToken = { runBlocking { devTokenProvider.developerToken() } },
            onToken = { token, storefront ->
                viewModelScope.launch {
                    runCatching { authStore.patch(mediaUserToken = token, storefront = storefront) }
                        .onFailure { e ->
                            YunoLog.e("LanLogin", "save credentials failed: ${e.message}")
                        }
                    server?.stop()
                    _lan.value = LanState.Done
                }
            }
        )
        lanServer = server
        if (!server.start(7373)) {
            lanServer = null
            _lan.value = LanState.Error("无法开启局域网端口")
            return
        }
        val ip = server.lanIp() ?: "<电视的局域网 IP>"
        _lan.value = LanState.Waiting("http://$ip:${server.port}")
    }

    fun cancelLanLogin() {
        lanServer?.stop()
        lanServer = null
        _lan.value = LanState.Idle
    }

    override fun onCleared() {
        lanServer?.stop()
        lanServer = null
    }

    // ---------------- 遥控动作转发（UI 主线程调用） ----------------

    fun togglePlayPause() = controller.playPause()

    fun next() = controller.next()

    fun previous() = controller.prev()

    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    fun playTrack(track: Track, queue: List<Track>) {
        viewModelScope.launch {
            val q = queue.ifEmpty { listOf(track) }
            val index = q.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            controller.playQueue(q, index)
        }
    }

    fun addToQueue(track: Track) = controller.addToQueue(track)

    fun playNext(track: Track) = controller.playNext(track)

    fun playAlbum(album: Album) {
        viewModelScope.launch {
            val detail = catalog.albumDetail(album.id) ?: return@launch
            if (detail.tracks.isEmpty()) return@launch
            controller.playQueue(detail.tracks, 0)
        }
    }

    fun playPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            val detail = library.playlistDetail(playlist.id) ?: return@launch
            if (detail.tracks.isEmpty()) return@launch
            controller.playQueue(detail.tracks, 0)
        }
    }
}