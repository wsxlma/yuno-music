package com.xiaoliu.yuno.core.network.auth

/**
 * 登录 Cookie 收割机。
 *
 * 关键事实（2026-10 调研确证）：
 *  - media-user-token 是 **HttpOnly Cookie**（不是 localStorage）——只能通过
 *    Android CookieManager.getCookie("https://music.apple.com") 读到，
 *    document.cookie 拿不到
 *  - 必须开启 setAcceptThirdPartyCookies(webView, true)：Apple 的登录常在
 *    iframe 内完成，没有顶层跳转，不开这个永远登不上
 *  - 除 media-user-token 外，Apple 网页播放器家族共 7 个 cookie：
 *    itspod / pltvcid / pldfltcid / itua / media-user-token / acn1 / dslang
 *    其中 itua 就是 storefront 两字母代码
 */
object CookieHarvester {

    /** 解析 CookieManager 返回的 "a=1; b=2" 串 */
    fun parseCookieHeader(raw: String?): Map<String, String> =
        raw.orEmpty().split(';').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.toMap()

    /**
     * 从 cookie 头里淘出凭据。media-user-token 不在 → null（还没登录完）。
     * @param raw CookieManager.getCookie("https://music.apple.com") 的原始值
     */
    /**
 * 清掉 WebView 里的全部 Cookie（退出登录用）。
 *
 * **真机 bug（2.0.0 Beta5）**：退出登录只清了 DataStore，但 WebView 里的
 * `media-user-token`（HttpOnly）还在 → 回到登录页时 [harvest] 立刻又收割到凭据
 * → `onLoggedIn` 又登回去，表现为「退出登录退不了，必须清除全部数据」。
 *
 * 退出登录必须**三处一起清**：DataStore + 内存快照 + WebView Cookie。
 */
fun clearAllCookies() {
    runCatching {
        val cm = android.webkit.CookieManager.getInstance()
        cm.removeAllCookies(null)
        cm.flush()
    }
}

fun harvest(raw: String?): AppleCredentials? {
        val jar = parseCookieHeader(raw)
        val mediaUserToken = jar["media-user-token"]?.takeIf { it.isNotBlank() } ?: return null
        return AppleCredentials(
            mediaUserToken = mediaUserToken,
            storefront = jar["itua"]?.takeIf { it.matches(Regex("[a-zA-Z]{2}")) }?.lowercase() ?: "us"
        )
    }
}
