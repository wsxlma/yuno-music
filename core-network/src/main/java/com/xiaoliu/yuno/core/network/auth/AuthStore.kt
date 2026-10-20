package com.xiaoliu.yuno.core.network.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.yunoAuthDataStore: DataStore<Preferences> by preferencesDataStore(name = "yuno_auth")

/**
 * Apple 账号凭据（个人使用，明文存 DataStore；设备本身有全盘加密，别慌）。
 *
 * - mediaUserToken：登录 Apple ID 后收割的 HttpOnly Cookie（Music-User-Token 头的值）
 * - developerToken：开发者 JWT（手动配置优先；否则从网页播放器 bundle 自动抓取）
 * - mediaApiToken：旧私有接口的遗留字段 —— 2026-10 调研确认现行实现都不再需要它
 *   （Media-API-Token 全站代码检索零命中），保留仅为兼容未来接口变动
 * - storefront：账号所在区（us / cn / jp...），登录时从 itua cookie / me/account 获取
 */
data class AppleCredentials(
    val mediaUserToken: String = "",
    val developerToken: String = "",
    val mediaApiToken: String = "",
    val storefront: String = "us",
    val accountName: String = "",
    val subscription: String = ""
) {
    val isLoggedIn: Boolean get() = mediaUserToken.isNotBlank()
}

class AuthStore(private val context: Context) {

    private object Keys {
        val MEDIA_USER_TOKEN = stringPreferencesKey("media_user_token")
        val DEVELOPER_TOKEN = stringPreferencesKey("developer_token")
        val MEDIA_API_TOKEN = stringPreferencesKey("media_api_token")
        val STOREFRONT = stringPreferencesKey("storefront")
        val ACCOUNT_NAME = stringPreferencesKey("account_name")
        val SUBSCRIPTION = stringPreferencesKey("subscription")
    }

    /** 内存快照：给需要同步读取凭据的调用点（例如后台队列解析） */
    @Volatile
    var cached: AppleCredentials = AppleCredentials()
        private set

    val credentials: Flow<AppleCredentials> = context.yunoAuthDataStore.data.map { prefs ->
        toCredentials(prefs).also { cached = it }
    }

    val isLoggedIn: Flow<Boolean> = credentials.map { it.isLoggedIn }

    /** App 启动时调用一次：把磁盘凭据灌进内存快照 */
    suspend fun prime() {
        cached = toCredentials(context.yunoAuthDataStore.data.first())
    }

    suspend fun save(value: AppleCredentials) {
        context.yunoAuthDataStore.edit { prefs ->
            prefs[Keys.MEDIA_USER_TOKEN] = value.mediaUserToken
            prefs[Keys.DEVELOPER_TOKEN] = value.developerToken
            prefs[Keys.MEDIA_API_TOKEN] = value.mediaApiToken
            prefs[Keys.STOREFRONT] = value.storefront
            prefs[Keys.ACCOUNT_NAME] = value.accountName
            prefs[Keys.SUBSCRIPTION] = value.subscription
        }
        cached = value
    }

    /** 部分更新：登录抓取阶段分步落盘用 */
    suspend fun patch(
        mediaUserToken: String? = null,
        developerToken: String? = null,
        mediaApiToken: String? = null,
        storefront: String? = null,
        accountName: String? = null,
        subscription: String? = null
    ) {
        save(
            cached.copy(
                mediaUserToken = mediaUserToken ?: cached.mediaUserToken,
                developerToken = developerToken ?: cached.developerToken,
                mediaApiToken = mediaApiToken ?: cached.mediaApiToken,
                storefront = storefront ?: cached.storefront,
                accountName = accountName ?: cached.accountName,
                subscription = subscription ?: cached.subscription
            )
        )
    }

    suspend fun clear() {
        context.yunoAuthDataStore.edit { it.clear() }
        cached = AppleCredentials()
    }

    private fun toCredentials(prefs: Preferences) = AppleCredentials(
        mediaUserToken = prefs[Keys.MEDIA_USER_TOKEN].orEmpty(),
        developerToken = prefs[Keys.DEVELOPER_TOKEN].orEmpty(),
        mediaApiToken = prefs[Keys.MEDIA_API_TOKEN].orEmpty(),
        storefront = prefs[Keys.STOREFRONT]?.takeIf { it.isNotBlank() } ?: "us",
        accountName = prefs[Keys.ACCOUNT_NAME].orEmpty(),
        subscription = prefs[Keys.SUBSCRIPTION].orEmpty()
    )
}
