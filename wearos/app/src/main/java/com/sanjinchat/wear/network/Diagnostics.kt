package com.sanjinchat.wear.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

data class ProbeResult(
    val label: String,
    val ok: Boolean,
    val detail: String,
    val durationMs: Long
)

object Diagnostics {

    suspend fun runAll(hostRaw: String, protocol: String): List<ProbeResult> = coroutineScope {
        val stripped = ServerEndpoint.stripScheme(hostRaw.trim().removeSuffix("/"))
        val hostPort = ServerEndpoint.normalizeHostPort(stripped)
        val host = hostPort.substringBeforeLast(':')
        val portStr = hostPort.substringAfterLast(':', "8181")
        val port = portStr.toIntOrNull() ?: 8181

        val out = mutableListOf<ProbeResult>()

        // 1. 目标信息
        out.add(ProbeResult("目标", true, "${if (protocol == "wss") "wss" else "ws"} → $host:$port", 0))

        // 2. DNS
        val dns = async(Dispatchers.IO) {
            var detail = ""
            var ok = true
            val ms = measureTimeMillis {
                try {
                    val addrs = InetAddress.getAllByName(host)
                    detail = addrs.joinToString(", ") { it.hostAddress }
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("DNS 解析", ok, detail, ms)
        }
        out.add(dns.await())

        // 3. TCP 8181 原始套接字
        val tcp = async(Dispatchers.IO) {
            var detail = ""
            var ok = true
            val ms = measureTimeMillis {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 5000)
                        detail = "TCP SYN/ACK 成功"
                    }
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("TCP 连 $port", ok, detail, ms)
        }
        out.add(tcp.await())

        // 4. HTTP (明文)
        val http = async(Dispatchers.IO) {
            var detail: String
            var ok = true
            val ms = measureTimeMillis {
                try {
                    val c = buildOkHttpClient("ws", false)
                    val req = Request.Builder().url("http://$host:$port/").get().build()
                    c.newCall(req).execute().use { r ->
                        detail = "HTTP ${r.code} ${r.message}"
                        ok = r.code in 200..399
                    }
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("HTTP :$port", ok, detail, ms)
        }
        out.add(http.await())

        // 5. HTTPS (宽容 TLS)
        val https = async(Dispatchers.IO) {
            var detail: String
            var ok = true
            val ms = measureTimeMillis {
                try {
                    val c = buildOkHttpClient("wss", false)
                    val req = Request.Builder().url("https://$host:$port/").get().build()
                    c.newCall(req).execute().use { r ->
                        detail = "HTTPS ${r.code} ${r.message}"
                        ok = r.code in 200..399
                    }
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("HTTPS :$port", ok, detail, ms)
        }
        out.add(https.await())

        // 6. WS 握手
        val ws = async(Dispatchers.IO) {
            var detail: String
            var ok = true
            val ms = measureTimeMillis {
                try {
                    detail = probeWs("ws", host, port, "probe-dummy")
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("WS 握手", ok, detail, ms)
        }
        out.add(ws.await())

        // 7. WSS 握手
        val wss = async(Dispatchers.IO) {
            var detail: String
            var ok = true
            val ms = measureTimeMillis {
                try {
                    detail = probeWs("wss", host, port, "probe-dummy")
                } catch (t: Throwable) {
                    ok = false
                    detail = t.explain()
                }
            }
            ProbeResult("WSS 握手", ok, detail, ms)
        }
        out.add(wss.await())

        out
    }

    private fun probeWs(protocol: String, host: String, port: Int, token: String): String {
        val latch = CountDownLatch(1)
        var message: String? = null
        val url = "$protocol://$host:$port/ws?token=$token"
        val client = buildOkHttpClient(protocol, forWs = true)
        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                message = "onOpen code=${response.code}"
                ws.close(1000, "probe done")
                latch.countDown()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val sb = StringBuilder()
                sb.append(t.explain())
                if (response != null) sb.append(" | HTTP ${response.code}")
                message = sb.toString()
                runCatching { latch.countDown() }
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                runCatching { latch.countDown() }
            }
        }
        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val done = latch.await(8, TimeUnit.SECONDS)
        if (!done) {
            ws.cancel()
            message = (message ?: "") + " [超时 8s]"
        }
        // 取消 client 不再需要的 idle 连接（避免 leak）
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        return message ?: "(无结果)"
    }
}

private fun Throwable.explain(): String {
    val cause = generateSequence(this) { it.cause }.lastOrNull() ?: this
    val cls = cause.javaClass.simpleName
    val msg = (cause.message ?: "").take(180)
    return "$cls: $msg"
}
