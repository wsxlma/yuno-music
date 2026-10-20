package com.xiaoliu.yuno.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.favoriteDataStore: DataStore<Preferences> by preferencesDataStore(name = "yuno_favorites")

/**
 * 本地收藏（DataStore，需求钦定 feature-library 的"本地收藏"）。
 * 只存曲目 id 集合；展示时与资料库歌曲合并。
 */
class FavoriteStore(private val context: Context) {

    private val key = stringSetPreferencesKey("favorite_track_ids")

    val ids: Flow<Set<String>> = context.favoriteDataStore.data.map { prefs ->
        prefs[key] ?: emptySet()
    }

    suspend fun toggle(trackId: String) {
        context.favoriteDataStore.edit { prefs ->
            val current = prefs[key] ?: emptySet()
            prefs[key] = if (trackId in current) current - trackId else current + trackId
        }
    }
}
