package com.sanjinchat.wear.network

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Host:port 解析 + 服务端端口默认值补齐。
 *
 * 服务端（server.js）同时在单一 8181 端口跑 ws:// 与 wss://。
 * 如果用户或旧缓存输入没有端口，客户端会按协议默认连 80/443 → connect reset。
 * 因此这里统一补齐。
 *
 * 识别规则（不简单用 contains(':') 以避免误判 IPv6）：
 *  - [xxx]:port  或  [xxx]        → 前者有端口，后者无
 *  - host:port(port 纯数字)         → 有端口
 *  - 其他（裸主机名、纯 IPv6 多冒号）→ 无端口，自动补 :8181
 */
object ServerEndpoint {
    private val TRAILING_PORT = Regex(""":(\d{1,5})$""")

    /** 返回 (host, port) 对，无端口时用 8181 */
    fun normalizeHostPort(raw: String): String {
        val s = raw.trim()
        if (s.isBlank()) return s
        val hostPart = when {
            s.startsWith('[') -> {
                val closeBracket = s.indexOf(']')
                if (closeBracket == -1) return "$s:8181"
                val after = s.substring(closeBracket + 1)
                if (after.startsWith(':')) {
                    // [host]:port 形式 → 已带端口
                    return s
                } else {
                    // [host] 无端口
                    return "$s:8181"
                }
            }
            else -> s
        }
        val m = TRAILING_PORT.find(hostPart)
        if (m != null) {
            val portStr = m.groupValues[1]
            val port = portStr.toIntOrNull()
            if (port != null && port in 1..65535) {
                // 末尾冒号后是合法端口号 → 直接返回
                // 但要排除 IPv6 末尾一段恰好 1-5 位且全数字的情况（概率极低）：
                // hostPart 含多个冒号 & 最后一段 4+ 位数字一般是 IPv6，保留原样。
                val colonCount = hostPart.count { it == ':' }
                if (colonCount >= 2 && portStr.length >= 4) {
                    // 判定为 IPv6（a:b:c:d:e:f:1234）而不是 host:port → 自动补端口
                    return "$hostPart:8181"
                }
                return hostPart
            }
        }
        return "$hostPart:8181"
    }

    /** 从形如 (ws/wss/http/https)://host:port/path 的地址中剥离协议前缀 */
    fun stripScheme(url: String): String {
        val s = url.trim()
        return when {
            s.startsWith("http://") -> s.substring(7)
            s.startsWith("https://") -> s.substring(8)
            s.startsWith("ws://") -> s.substring(5)
            s.startsWith("wss://") -> s.substring(6)
            else -> s
        }
    }
}

/**
 * 宽容 TLS + 明确协议版本，用于 WSS / HTTPS 连接。
 *
 * 服务端证书通常是自签名（/CN=localhost，非系统 CA 颁发），且用户偶尔会出现
 * 「主机名不匹配」或「TLSv1.3 协商失败」导致 SSLException/Connection reset。
 * 这里：
 *  - 先 TLSv1.3 → 回退 TLSv1.2 → 回退默认 "TLS"（兼容旧 Android 手表）
 *  - trustAllCerts + HostnameVerifier 全放行
 *  - OkHttp 同时挂 MODERN_TLS 与 CLEARTEXT 连接规范（ws 模式不走 TLS）
 */
object PermissiveTls {

    private val trustAllCerts: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )

    private val permissiveSslContext: SSLContext by lazy {
        sequenceOf("TLSv1.3", "TLSv1.2", "TLS")
            .mapNotNull { proto ->
                runCatching {
                    SSLContext.getInstance(proto).apply {
                        init(null, trustAllCerts, SecureRandom())
                    }
                }.getOrNull()
            }
            .firstOrNull()
            ?: error("No TLS SSLContext available on this device")
    }

    fun apply(builder: OkHttpClient.Builder) {
        builder
            .sslSocketFactory(
                permissiveSslContext.socketFactory,
                trustAllCerts[0] as X509TrustManager
            )
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
    }
}

/** 构建 OkHttp 客户端：wss/https 走宽容 TLS + MODERN_TLS，ws/http 走 CLEARTEXT。 */
fun buildOkHttpClient(protocol: String, forWs: Boolean = false): OkHttpClient {
    val b = OkHttpClient.Builder()
    if (forWs) {
        b.connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
    } else {
        b.connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
    }
    b.retryOnConnectionFailure(true)
    if (protocol == "wss") {
        PermissiveTls.apply(b)
        b.connectionSpecs(
            listOf(
                ConnectionSpec.MODERN_TLS,
                ConnectionSpec.COMPATIBLE_TLS,
                ConnectionSpec.CLEARTEXT
            )
        )
    } else {
        b.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.MODERN_TLS))
    }
    return b.build()
}
