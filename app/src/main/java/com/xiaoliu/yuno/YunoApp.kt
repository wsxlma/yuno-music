package com.xiaoliu.yuno

import android.app.Application
import com.xiaoliu.yuno.di.AppGraph

class YunoApp : Application() {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}
