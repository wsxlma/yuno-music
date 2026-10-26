package com.xiaoliu.yuno.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.xiaoliu.yuno.feature.tv.TvViewModel

/**
 * 电视专版组合根 —— 只装配 TV 侧 ViewModel。
 * （手机专版的 AppGraph 在 phone 源码集里，本文件不会进手机包。）
 */
class AppGraph(app: Application) : AppGraphCore(app) {

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            TvViewModel(
                catalog = catalogRepository,
                library = libraryRepository,
                lyricsRepository = lyricsRepository,
                controller = playbackController,
                authStore = authStore,
                devTokenProvider = devTokenProvider
            )
        }
    }
}