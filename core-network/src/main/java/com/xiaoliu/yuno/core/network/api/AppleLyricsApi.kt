package com.xiaoliu.yuno.core.network.api

import com.xiaoliu.yuno.core.network.Http
import com.xiaoliu.yuno.core.network.auth.AuthStore
import com.xiaoliu.yuno.core.network.auth.DevTokenProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Apple 私有歌词接口（amp-api.music.apple.com）。
 *
 * 端点（调研确证 2026-10）：
 *  - /v1/catalog/{sf}/songs/{id}/syllable-lyrics  ← 逐字（更新）
 *  - /v1/catalog/{sf}/songs/{id}/lyrics            ← 行级（兜底）
 *  TTML 在返回 JSON 的 data[0].attributes.ttml
 *
 * 头：Authorization Bearer（dev token）+ Media-User-Token + Origin。
 * Media-API-Token 这个旧 header 现行实现都不再用（全站检索零命中），不实现。
 */
class AppleLyricsApi(
    private val auth: AuthStore,
    private val tokenProvider: DevTokenProvider
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 抓 TTML：先逐字端点，再行级端点；都没有 → null（上层去 LRCLIB 兜底） */
    suspend fun fetchTtml(songId: String): String? {
        fetch(songId, "syllable-lyrics")?.let { return it }
        return fetch(songId, "lyrics")
    }

    private suspend fun fetch(songId: String, endpoint: String): String? = runCatching {
        val devToken = tokenProvider.developerToken() ?: return@runCatching null
        val storefront = auth.cached.storefront.ifBlank { "us" }
        val url = "https://amp-api.music.apple.com/v1/catalog/$storefront/songs/$songId/$endpoint"

        val headers = mutableMapOf(
            "Authorization" to "Bearer $devToken",
            "Origin" to "https://music.apple.com"
        )
        auth.cached.mediaUserToken.takeIf { it.isNotBlank() }?.let {
            headers["Media-User-Token"] = it
        }

        val root = json.parseToJsonElement(Http.get(url, headers)) as? JsonObject
            ?: return@runCatching null
        val attrs = (root["data"] as? JsonArray)?.firstOrNull()
            ?.let { it as? JsonObject }?.get("attributes") as? JsonObject
        attrs?.get("ttml")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
}
