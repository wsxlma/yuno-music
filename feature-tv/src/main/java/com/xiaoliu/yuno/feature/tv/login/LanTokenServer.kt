package com.xiaoliu.yuno.feature.tv.login

import com.xiaoliu.yuno.core.network.YunoLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网登录服务器 —— TV 版登录的唯一入口（真机口径）：
 *   1. TV 在本地端口起一个迷你 HTTP 服务（ServerSocket 手搓，零依赖）；
 *   2. 手机浏览器打开 `http://<TV 的局域网 IP>:<端口>/`；
 *   3. 页面两条路：
 *      · **MusicKit JS 授权**：在 Apple 官方域名完成登录，musicUserToken
 *        由 JS 从内存取出自动 POST 回来（这正是 media-user-token）；
 *      · **手动粘贴兜底**：页面上贴令牌提交。
 *   4. POST /token → [onToken] 回调进 TV，落 AuthStore。
 *
 * 安全边界：只监听局域网、只在登录期间存活，收到令牌即关；不写任何文件。
 */
class LanTokenServer(
    private val devToken: () -> String?,
    private val onToken: (token: String, storefront: String?) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null

    @Volatile
    var port: Int = 0
        private set

    /** 本机局域网 IPv4（有线/无线都试），拿不到返回 null */
    fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    fun start(preferredPort: Int = 7373): Boolean {
        try {
            server = ServerSocket(preferredPort)
            running.set(true)
            port = server!!.localPort
            Thread({ acceptLoop() }, "yuno-lan-login").start()
            YunoLog.d("LanLogin", "server on port $port")
            return true
        } catch (e: Exception) {
            YunoLog.e("LanLogin", "start failed: ${e.message}")
            server = null
            return false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                server?.accept() ?: break
            } catch (_: Exception) {
                break
            }
            Thread({ handle(socket) }, "yuno-lan-http").start()
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLine(input).orEmpty()
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }

            if (requestLine.startsWith("POST /token", ignoreCase = true)) {
                val body = ByteArray(contentLength.coerceIn(0, 16 * 1024))
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                handleToken(String(body, 0, read, StandardCharsets.UTF_8), output)
            } else {
                val page = loginPage(devToken())
                write(output, "200 OK", "text/html; charset=utf-8", page.toByteArray(StandardCharsets.UTF_8))
            }
        } catch (e: Exception) {
            YunoLog.d("LanLogin", "handle: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun handleToken(body: String, output: java.io.OutputStream) {
        val params = body.split('&').filter { it.contains('=') }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
            k to v
        }
        val token = params["token"].orEmpty().trim()
        // storefront：宽松一点，真正值会被 me/account 权威回写覆盖
        val storefront = params["storefront"]?.trim()
            ?.takeIf { it.isNotBlank() && it.length <= 8 && it.all { c -> c.isLetterOrDigit() || c == '-' } }

        if (token.length !in 8..16_384 || !token.contains('.')) {
            YunoLog.d("LanLogin", "reject malformed token (len=${token.length})")
            write(output, "400 Bad Request", "text/plain; charset=utf-8", "bad token".toByteArray())
            return
        }
        YunoLog.d("LanLogin", "token received (len=${token.length}), storefront=$storefront")
        onToken(token, storefront)
        write(output, "200 OK", "text/plain; charset=utf-8", "OK".toByteArray())
    }

    private fun write(
        output: java.io.OutputStream,
        status: String,
        contentType: String,
        body: ByteArray
    ) {
        val header = (
            "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    /** 登录页：MusicKit JS 授权优先，手动粘贴兜底 */
    private fun loginPage(dev: String?): String {
        val escaped = dev?.replace("\\", "\\\\")?.replace("'", "\\'")?.replace("</", "<\\/") ?: ""
        return """
<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Yuno TV 登录</title>
<style>
  body{background:#0f0f14;color:#eee;font-family:system-ui,-apple-system,sans-serif;
       display:flex;flex-direction:column;align-items:center;padding:36px 16px;gap:20px;margin:0}
  h2{margin:0;color:#fff}
  .card{background:#1c1c24;border:1px solid #2c2c38;border-radius:16px;padding:22px;
        max-width:440px;width:100%;display:flex;flex-direction:column;gap:12px;box-sizing:border-box}
  button{background:#8b7bf7;border:0;color:#fff;border-radius:12px;padding:14px;font-size:16px}
  input{background:#0c0c10;border:1px solid #34343f;color:#eee;border-radius:10px;
         padding:12px;font-size:15px;width:100%;box-sizing:border-box}
  .ok{color:#7ee2a8}
  .dim{color:#9a9aa5;font-size:13px}
</style>
</head>
<body>
<h2>Yuno TV 登录</h2>
<div class="card">
  <button id="btn" onclick="authorize()">在 Apple 登录并自动获取令牌</button>
  <div id="st" class="dim"></div>
</div>
<div class="card">
  <div class="dim">或手动粘贴 media-user-token（兜底）：</div>
  <input id="tok" placeholder="令牌（可先登录 music.apple.com 再复制）">
  <input id="sf" placeholder="storefront（us / cn / jp 等，留空自动）">
  <button onclick="submitManual()">提交到电视</button>
  <div id="st2" class="dim"></div>
</div>
<script src="https://js-cdn.music.apple.com/musickit/v3/musickit.js"></script>
<script>
var devToken = '$escaped';
var music = null;
function workbox(el){ return document.getElementById(el); }
function initMusicKit(){
  try {
    if (typeof MusicKit === 'undefined') { throw new Error('MusicKit 脚本加载失败'); }
    MusicKit.configure({ developerToken: devToken, app: { name: 'Yuno', build: 'tv' } });
    music = MusicKit.getInstance();
    return true;
  } catch (e) {
    workbox('st').textContent = 'MusicKit 初始化失败：' + e.message + '，请用手动粘贴';
    return false;
  }
}
function sendToken(token, sf, el){
  var body = 'token=' + encodeURIComponent(token) + '&storefront=' + encodeURIComponent(sf || '');
  fetch('/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: body })
    .then(function(r){ return r.text(); })
    .then(function(){ workbox(el).innerHTML = '<span class="ok">已发送，回电视上看看 ✨</span>'; })
    .catch(function(){ workbox(el).textContent = '送回失败，请重新提交'; });
}
function authorize(){
  if (!initMusicKit()) return;
  workbox('st').textContent = '即将打开 Apple 登录…';
  music.authorize().then(function(){
    var t = music.musicUserToken || '';
    var sf = music.storefrontId || '';
    workbox('st').innerHTML = '<span class="ok">拿到令牌，正在送回 TV…</span>';
    sendToken(t, sf, 'st');
  }).catch(function(e){
    workbox('st').textContent = '登录失败或已取消：' + (e && e.message ? e.message : '') + '（可重试，或手动粘贴）';
  });
}
function submitManual(){
  var t = workbox('tok').value.trim();
  if (!t) { workbox('st2').textContent = '请先粘贴令牌'; return; }
  sendToken(t, workbox('sf').value.trim(), 'st2');
}
</script>
</body>
</html>
""".trimIndent()
    }
}