package com.xiaoliu.yuno.core.network.auth

import android.util.Base64
import com.xiaoliu.yuno.core.network.Http
import com.xiaoliu.yuno.core.network.YunoLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Developer Token 供给层 —— 两条链路、互为兜底：
 *
 *  1. 手动配置（AuthStore.developerToken）：自己有 Apple Developer 账号 + .p8 签的 JWT 时最稳
 *  2. 网页抓取：从 music.apple.com 首页找 /assets/index[~-]*.js，正则抓内嵌 JWT。
 *     bundle 里通常有 2~3 个 JWT（AMPWebPlay / 团队 token），
 *     **优选 iss=AMPWebPlay 的网页播放器令牌**（实测它能通行 api.music.apple.com 与 amp-api，
 *     前提：请求带 Origin: https://music.apple.com，见 AppleMusicApi.headers）。
 *
 * 抓来的 token 约 70~84 天有效，这里做 12 小时缓存 + exp 校验 + 过期自动重抓。
 */
class DevTokenProvider(private val auth: AuthStore) {

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var scrapedToken: String? = null

    @Volatile
    private var scrapedAtMs: Long = 0L

    suspend fun developerToken(force: Boolean = false): String? {
        // 1. 手动配置优先
        val manual = auth.cached.developerToken
        if (!force && manual.isNotBlank()) return manual

        // 2. 内存缓存
        val cached = scrapedToken
        if (!force && cached != null &&
            System.currentTimeMillis() - scrapedAtMs < CACHE_TTL_MS &&
            !isExpired(cached)
        ) {
            return cached
        }

        // 3. 重新抓（互斥，防并发重复抓）
        return mutex.withLock {
            val fresh = scrapedToken
            if (!force && fresh != null &&
                System.currentTimeMillis() - scrapedAtMs < CACHE_TTL_MS &&
                !isExpired(fresh)
            ) {
                return@withLock fresh
            }
            val scraped = scrape()
            if (scraped != null) {
                scrapedToken = scraped
                scrapedAtMs = System.currentTimeMillis()
                scraped
            } else {
                // 抓失败：宁可拿旧 token 顶着（可能还能用），也不要空手
                cached?.takeIf { !isExpired(it) }
            }
        }
    }

    private suspend fun scrape(): String? = runCatching {
        val html = Http.get("https://music.apple.com/")
        val jsPath = INDEX_JS.find(html)?.value ?: return@runCatching null
        val js = Http.get("https://music.apple.com$jsPath")
        val candidates = TOKEN_PATTERN.findAll(js)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        // 优选网页播放器令牌（payload 含 AMPWebPlay），失败退第一个未过期的
        val picked = candidates.firstOrNull { !isExpired(it) && payloadOf(it)?.contains("AMPWebPlay") == true }
            ?: candidates.firstOrNull { !isExpired(it) }
        YunoLog.d("Token", "scraped ${candidates.size} jwt candidates, picked=${picked != null} (len=${picked?.length})")
        picked
    }.getOrElse { e ->
        YunoLog.e("Token", "dev token scrape failed: ${e.message}")
        null
    }

    /** 解析 JWT payload（不校验签名，本机用途） */
    private fun payloadOf(jwt: String): String? = runCatching {
        val payload = jwt.split('.').getOrNull(1) ?: return null
        String(
            Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        )
    }.getOrNull()

    /** 本地过期防护：解析 exp，比当前时间早（留 1 分钟余量）即视为过期 */
    fun isExpired(jwt: String): Boolean = runCatching {
        val decoded = payloadOf(jwt) ?: return false
        val exp = (json.parseToJsonElement(decoded) as? JsonObject)
            ?.get("exp")?.jsonPrimitive?.longOrNull
            ?: return false
        exp * 1000L < System.currentTimeMillis() + 60_000L
    }.getOrDefault(false)

    companion object {
        private const val CACHE_TTL_MS = 12L * 60L * 60L * 1000L

        /** 找网页播放器的入口 bundle（现行格式 assets/index(~|-)hash.js） */
        private val INDEX_JS = Regex("""/assets/index[~-][^/"]+\.js""")

        /** 抓 JWT：gamdl 现行正则（不再假设 eyJh 前缀） */
        private val TOKEN_PATTERN =
            Regex("""(eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_]+)""")
    }
}
