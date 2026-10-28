package com.xiaoliu.yuno.di

import android.app.Application
import com.xiaoliu.yuno.core.network.AudioQuality
import com.xiaoliu.yuno.core.network.FxPref
import com.xiaoliu.yuno.core.network.LyricsSourcePref
import com.xiaoliu.yuno.core.network.YunoLog
import com.xiaoliu.yuno.core.network.api.AppleLyricsApi
import com.xiaoliu.yuno.core.network.api.AppleMusicApi
import com.xiaoliu.yuno.core.network.api.ApplePlaybackApi
import com.xiaoliu.yuno.core.network.api.LrcLibApi
import com.xiaoliu.yuno.core.network.auth.AppleCredentials
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import com.xiaoliu.yuno.core.playback.AppleWidevine
import com.xiaoliu.yuno.core.playback.PlaybackController
import com.xiaoliu.yuno.core.ui.component.LyricFont
import com.xiaoliu.yuno.data.playback.AppleStreamResolver
import com.xiaoliu.yuno.data.repository.CatalogRepository
import com.xiaoliu.yuno.data.repository.FavoriteStore
import com.xiaoliu.yuno.data.repository.LibraryRepository
import com.xiaoliu.yuno.data.repository.LocalCache
import com.xiaoliu.yuno.data.repository.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * AppGraph 的共享基类 —— 网络/数据/播放三层与启动流程，手机专版（phone 源码集）
 * 与电视专版（tv 源码集）各有一个 AppGraph 子类，只添加各自独有的 ViewModel 工厂。
 * 依赖方向永远单向：feature → data → core-network / core-playback → core-model。
 */
open class AppGraphCore(protected val app: Application) {

    // ---------------- 网络层 ----------------

    val authStore = AuthStore(app)
    val devTokenProvider = DevTokenProvider(authStore)
    val appleMusicApi = AppleMusicApi(authStore, devTokenProvider)
    val appleLyricsApi = AppleLyricsApi(authStore, devTokenProvider)
    val applePlaybackApi = ApplePlaybackApi(authStore, devTokenProvider)
    val lrcLibApi = LrcLibApi()

    // ---------------- 数据层 ----------------

    val catalogRepository = CatalogRepository(appleMusicApi)
    val favoriteStore = FavoriteStore(app)
    val libraryRepository = LibraryRepository(appleMusicApi, favoriteStore, authStore)
    val localCache = LocalCache(app.cacheDir)
    val lyricsRepository = LyricsRepository(
        appleLyrics = appleLyricsApi,
        lrcLib = lrcLibApi,
        cacheDir = File(app.cacheDir, "lyrics")
    )
    val streamResolver = AppleStreamResolver(applePlaybackApi, appleMusicApi)

    // ---------------- 播放层 ----------------

    val playbackController = PlaybackController(app, streamResolver)

    // ---------------- 启动 ----------------

    /** 凭据读完前不闪登录页（DataStore 首读是异步的） */
    private val _authPrimed = MutableStateFlow(false)
    val authPrimed: StateFlow<Boolean> = _authPrimed.asStateFlow()

    protected val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        // 运行日志落盘（资库页「导出日志」一键分享用；不含任何令牌）
        YunoLog.init(File(app.getExternalFilesDir(null) ?: app.filesDir, "logs/yuno.log"))
        YunoLog.d(
            "App",
            "Yuno start, version=" + runCatching {
                app.packageManager.getPackageInfo(app.packageName, 0).versionName
            }.getOrNull()
        )

        // 连接 PlaybackService（MediaController；ExoPlayer 在服务里，这里只是遥控器）
        playbackController.connect()

        // 音质偏好读入内存（ApplePlaybackApi 选 flavor 时用）
        AudioQuality.init(app)

        // 歌词源偏好读入内存（LyricsRepository 按模式分派取词顺序）
        LyricsSourcePref.init(app)

        // 歌词特效偏好（简单/全屏两组，设置页与歌词页特效入口读取）
        FxPref.init(app)

        // 歌词字体偏好读入内存（LyricsView/LandscapePlayer 渲染时取）
        LyricFont.init(app)

        // Widevine DRM 凭据入口（全曲 cbcs 解密用；在服务创建播放器前配置好）
        AppleWidevine.configure(authStore, devTokenProvider)

        appScope.launch {
            // 防御：DataStore 读取异常也绝不能卡死「已就绪」信号（否则 UI 永久黑屏）
            runCatching { authStore.prime() }
            _authPrimed.value = true

            // 预热 developer token（网页抓取或手动配置），失败无所谓，用时会重试
            runCatching { devTokenProvider.developerToken() }
        }
    }

    // ---------------- 登录 / 退出（两个专版共用同一套凭据机制） ----------------

    /** 登录收割成功后落盘 */
    suspend fun onLoggedIn(credentials: AppleCredentials) {
        authStore.save(credentials)
        YunoLog.d(
            "Auth",
            "credentials saved (storefront=${credentials.storefront}, tokenLen=${credentials.mediaUserToken.length})"
        )
        runCatching { devTokenProvider.developerToken() }
    }

    /** 退出登录（手机版：资料库页账户弹层触发；TV 版同样可用） */
    suspend fun logout() {
        runCatching { playbackController.pause() }
        // 三处一起清（缺一个都退不掉）：
        //   ① WebView Cookie —— 不清的话登录页会立刻自动收割又登回去
        //   ② DataStore 落盘凭据
        //   ③ 内存快照
        runCatching { com.xiaoliu.yuno.core.network.auth.CookieHarvester.clearAllCookies() }
        runCatching { authStore.clear() }
        YunoLog.d("App", "logged out (cookies + datastore cleared)")
    }

    /**
     * 退出登录 —— 跑在 AppGraph 自己的 appScope 上。
     *
     * 真机 bug（2.0.0 Beta2）：旧法用 Composable 的 rememberCoroutineScope().launch，
     * 设置面板一关那个 scope 就回收、协程被取消 → authStore.clear() 没执行完，
     * 表现为「退出登录不管用」。这里跟 UI 生命周期解耦，一定跑完。
     */
    fun logoutNow() {
        appScope.launch {
            runCatching { logout() }.onFailure {
                YunoLog.d("App", "logout failed: ${it.message}")
            }
        }
    }
}