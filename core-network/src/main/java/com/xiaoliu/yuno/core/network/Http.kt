package com.xiaoliu.yuno.core.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 OkHttp 单例 + 协程扩展。
 *
 * 所有 Apple Music / LRCLIB 请求共用这一个客户端（连接池、DNS 缓存共享）。
 * 认证头由各 Api 类逐请求传入 —— 个人客户端不搞全局拦截器魔法，
 * 哪个接口要什么头在代码里能一眼看全，排错方便。
 */
object Http {

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val DEFAULT_UA = "Yuno/1.0 (personal use; contact: xaehdb@gmail.com)"

    private fun Request.Builder.commonYunoHeaders(headers: Map<String, String>) = apply {
        // 调用方显式传了 User-Agent 就不覆盖（LRCLIB 要求署名 UA）
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            header("User-Agent", DEFAULT_UA)
        }
    }

    /** GET 文本，非 2xx 抛 IOException；每次请求进运行日志（URL 无敏感信息） */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runLogged("GET", url) {
            executeText(client.newCall(builder.commonYunoHeaders(headers).build()))
        }
    }

    /** POST JSON，非 2xx 抛 IOException */
    suspend fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): String {
        val builder = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runLogged("POST", url) {
            executeText(client.newCall(builder.commonYunoHeaders(headers).build()))
        }
    }

    /** POST 二进制（Widevine provision 用），非 2xx 抛 IOException；返回响应原始字节 */
    suspend fun postBytes(
        url: String,
        body: ByteArray,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(null))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runLoggedBytes("POST", url) {
            executeBytes(client.newCall(builder.commonYunoHeaders(headers).build()))
        }
    }

    /** GET 二进制（fMP4 init 段抓取用），非 2xx 抛 IOException；返回响应原始字节 */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): ByteArray {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        return runLoggedBytes("GET", url) {
            executeBytes(client.newCall(builder.commonYunoHeaders(headers).build()))
        }
    }

    private suspend fun runLoggedBytes(
        method: String,
        url: String,
        block: suspend () -> ByteArray
    ): ByteArray {
        return try {
            val body = block()
            YunoLog.d("HTTP", "$method $url -> OK ${body.size}B")
            body
        } catch (e: IOException) {
            YunoLog.e("HTTP", "$method $url -> FAIL: ${e.message}")
            throw e
        }
    }

    private suspend fun executeBytes(call: Call): ByteArray {
        val response = call.await()
        return response.use { res ->
            if (!res.isSuccessful) {
                val body = res.body?.string().orEmpty().take(300)
                throw IOException("HTTP ${res.code} ${res.message} for ${res.request.url} — $body")
            }
            res.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun runLogged(method: String, url: String, block: suspend () -> String): String {
        return try {
            val body = block()
            YunoLog.d("HTTP", "$method $url -> OK ${body.length}B")
            body
        } catch (e: IOException) {
            // e.message 含 "HTTP 4xx ... 错误体前 300 字符"（错误诊断的关键线索）
            YunoLog.e("HTTP", "$method $url -> FAIL: ${e.message}")
            throw e
        }
    }

    private suspend fun executeText(call: Call): String {
        val response = call.await()
        return response.use { res ->
            if (!res.isSuccessful) {
                val body = res.body?.string().orEmpty().take(300)
                throw IOException("HTTP ${res.code} ${res.message} for ${res.request.url} — $body")
            }
            res.body?.string() ?: ""
        }
    }
}

/** OkHttp Call → 挂起函数（取消时自动 cancel 请求） */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!cont.isCancelled) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {
        }
    }
}
