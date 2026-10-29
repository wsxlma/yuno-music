package com.xiaoliu.yuno.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xiaoliu.yuno.feature.detail.AlbumDetailViewModel
import com.xiaoliu.yuno.feature.detail.PlaylistDetailViewModel
import com.xiaoliu.yuno.feature.home.HomeViewModel
import com.xiaoliu.yuno.feature.library.AlbumWallViewModel
import com.xiaoliu.yuno.feature.player.PlayerViewModel
import com.xiaoliu.yuno.feature.search.SearchViewModel

/**
 * 手机专版组合根 —— 只装配手机侧 ViewModel。
 * （TV 专版的 AppGraph 在 tv 源码集里，本文件不会进 TV 包。）
 */
class AppGraph(app: Application) : AppGraphCore(app) {

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(catalogRepository, localCache, playbackController) }
        initializer { SearchViewModel(catalogRepository, playbackController) }
        initializer { AlbumWallViewModel(libraryRepository, localCache, playbackController) }
        initializer { PlayerViewModel(playbackController, lyricsRepository, favoriteStore) }
        initializer {
            val handle = createSavedStateHandle()
            AlbumDetailViewModel(
                albumId = handle.get<String>("albumId").orEmpty(),
                catalog = catalogRepository,
                library = libraryRepository,
                cache = localCache,
                controller = playbackController
            )
        }
        initializer {
            val handle = createSavedStateHandle()
            PlaylistDetailViewModel(
                playlistId = handle.get<String>("playlistId").orEmpty(),
                library = libraryRepository,
                cache = localCache,
                controller = playbackController
            )
        }
    }
}