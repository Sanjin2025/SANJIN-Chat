// ==================== HTTP 网络请求客户端（OkHttp + Kotlinx Serialization） ====================
package com.sanjin.wear

import com.sanjin.wear.model.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP API 客户端
 * 提供所有 REST 接口的简化调用：登录、注册、消息、好友、群聊等
 */
object ApiClient {

    /** JSON 解析器：忽略未知字段 */
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** OkHttp 客户端 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 协议 + 地址 完整 URL（HTTP/HTTPS 专用，用于 REST API）
     *
     *  支持的输入格式（用户在服务器地址栏可填写任意一种）：
     *   - 裸地址：host:port              → 默认 https://host:port（可通过显式前缀修改）
     *   - http://host:port                → http://host:port
     *   - https://host:port               → https://host:port
     *   - ws://host:port                  → 转 http://host:port（WS 意图下的 API 请求走明文）
     *   - wss://host:port                 → 转 https://host:port（WSS 意图下的 API 请求走加密）
     */
    fun fullUrl(server: String, path: String): String {
        val base = server.trimEnd('/')
        val (httpScheme, hostPart) = when {
            base.startsWith("ws://")  -> "http://"  to base.removePrefix("ws://")
            base.startsWith("wss://") -> "https://" to base.removePrefix("wss://")
            base.startsWith("http://")  -> "http://"  to base.removePrefix("http://")
            base.startsWith("https://") -> "https://" to base.removePrefix("https://")
            else -> "https://" to base   // 默认加密连接
        }
        val prefix = httpScheme + hostPart
        return if (path.startsWith("/")) prefix + path else "$prefix/$path"
    }

    /** 统一的请求头（含 Bearer Token，可选） */
    private fun Request.Builder.authHeader(token: String?): Request.Builder {
        if (!token.isNullOrBlank()) this.header("Authorization", "Bearer $token")
        this.header("Accept", "application/json")
        return this
    }

    /** ====== 通用 POST JSON ====== */
    suspend fun <T> postJson(
        server: String,
        path: String,
        body: Any? = null,
        token: String? = null,
        decoder: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val url = fullUrl(server, path)
        val jsonStr = if (body != null) json.encodeToString(body) else "{}"
        val reqBody = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(reqBody)
            .authHeader(token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${text.ifBlank { resp.message }}")
            }
            decoder(text)
        }
    }

    /** ====== 通用 GET ====== */
    suspend fun <T> get(
        server: String,
        path: String,
        token: String? = null,
        decoder: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val url = fullUrl(server, path)
        val req = Request.Builder()
            .url(url)
            .get()
            .authHeader(token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${text.ifBlank { resp.message }}")
            }
            decoder(text)
        }
    }

    /** ====== 通用 PUT JSON ====== */
    suspend fun <T> putJson(
        server: String,
        path: String,
        body: Any? = null,
        token: String? = null,
        decoder: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val url = fullUrl(server, path)
        val jsonStr = if (body != null) json.encodeToString(body) else "{}"
        val reqBody = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .put(reqBody)
            .authHeader(token)
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${text.ifBlank { resp.message }}")
            }
            decoder(text)
        }
    }

    /** ====== 通用 DELETE ====== */
    suspend fun <T> deleteJson(
        server: String,
        path: String,
        body: Any? = null,
        token: String? = null,
        decoder: (String) -> T
    ): T = withContext(Dispatchers.IO) {
        val url = fullUrl(server, path)
        val builder = Request.Builder()
            .url(url)
            .delete()
            .authHeader(token)
        if (body != null) {
            val jsonStr = json.encodeToString(body)
            val reqBody = jsonStr.toRequestBody("application/json; charset=utf-8".toMediaType())
            builder.delete(reqBody)
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw Exception("HTTP ${resp.code}: ${text.ifBlank { resp.message }}")
            }
            decoder(text)
        }
    }

    // ================================================================
    // 便捷方法：解析为 ApiResponse<T> 的快捷方式
    // ================================================================

    /** 把字符串解析为 ApiResponse（不关心 data 字段的泛型，避免为 Unit 查找序列化器） */
    private fun parseResp(text: String): ApiResponse<*> = json.decodeFromString(text)

    suspend fun register(server: String, username: String, password: String, nickname: String? = null): ApiResponse<*> {
        val body = mapOf(
            "username" to username,
            "password" to password
        ) + if (nickname.isNullOrBlank()) emptyMap() else mapOf("nickname" to nickname)
        return postJson(server, "/register", body, null, ::parseResp)
    }

    suspend fun login(server: String, username: String, password: String): ApiResponse<*> {
        val body = mapOf("username" to username, "password" to password)
        return postJson(server, "/login", body, null, ::parseResp)
    }

    suspend fun pairLogin(server: String, code: String): ApiResponse<*> {
        val body = mapOf("pairCode" to code)
        return postJson(server, "/api/pair/login", body, null, ::parseResp)
    }

    suspend fun getFriends(server: String, token: String): ApiResponse<*> {
        return get(server, "/friends", token, ::parseResp)
    }

    suspend fun getGroups(server: String, token: String): ApiResponse<*> {
        return get(server, "/groups", token, ::parseResp)
    }

    suspend fun getFriendRequests(server: String, token: String): ApiResponse<*> {
        return get(server, "/friend_requests", token, ::parseResp)
    }

    suspend fun getPrivateMessages(server: String, token: String, friendId: String): ApiResponse<*> {
        return get(server, "/messages/private/$friendId", token, ::parseResp)
    }

    suspend fun getGroupMessages(server: String, token: String, groupId: String): ApiResponse<*> {
        return get(server, "/messages/group/$groupId", token, ::parseResp)
    }

    suspend fun getAnnouncements(server: String): ApiResponse<*> {
        return get(server, "/api/announcements", null, ::parseResp)
    }

    suspend fun getServerInfo(server: String): ApiResponse<*> {
        return get(server, "/api/server/info", null, ::parseResp)
    }

    suspend fun getUser(server: String, userId: String, token: String? = null): ApiResponse<*> {
        return get(server, "/user/$userId", token, ::parseResp)
    }

    suspend fun searchUsers(server: String, token: String, keyword: String): ApiResponse<*> {
        val body = mapOf("keyword" to keyword)
        return postJson(server, "/search/users", body, token, ::parseResp)
    }

    suspend fun searchGroups(server: String, token: String, keyword: String): ApiResponse<*> {
        val body = mapOf("keyword" to keyword)
        return postJson(server, "/search/groups", body, token, ::parseResp)
    }

    suspend fun deleteAccount(server: String, token: String, password: String): ApiResponse<*> {
        val body = mapOf("password" to password)
        return deleteJson(server, "/api/user", body, token, ::parseResp)
    }

    suspend fun updateNickname(server: String, token: String, nickname: String): ApiResponse<*> {
        val body = mapOf("nickname" to nickname)
        return putJson(server, "/user/nickname", body, token, ::parseResp)
    }

    suspend fun updateAvatarEmoji(server: String, token: String, emoji: String): ApiResponse<*> {
        val body = mapOf("emoji" to emoji)
        return putJson(server, "/api/user/avatar/emoji", body, token, ::parseResp)
    }
}
