package com.sanjinchat.wear.network

import android.content.Context
import com.sanjinchat.wear.model.ChatMessage
import com.sanjinchat.wear.model.Friend
import com.sanjinchat.wear.model.FriendRequest
import com.sanjinchat.wear.model.GroupInfo
import com.sanjinchat.wear.model.GroupMembership
import com.sanjinchat.wear.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ApiClient(private val context: Context) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private var _client: OkHttpClient? = null
    private var _clientProtocol: String? = null

    private fun client(): OkHttpClient {
        val p = serverProtocol
        val cached = _client
        if (cached != null && _clientProtocol == p) return cached
        val fresh = buildOkHttpClient(protocol = p, forWs = false)
        _client = fresh
        _clientProtocol = p
        return fresh
    }

    var serverAddress: String = ""
        set(value) {
            field = value.trim().removeSuffix("/")
        }

    var serverProtocol: String = "ws"
        set(value) {
            val np = if (value == "wss") "wss" else "ws"
            if (field != np) {
                field = np
                _client = null
                _clientProtocol = null
            }
        }

    private fun baseUrl(): String {
        val cleaned = serverAddress.trim().removeSuffix("/")
        val withoutScheme = ServerEndpoint.stripScheme(cleaned)
        val withPort = ServerEndpoint.normalizeHostPort(withoutScheme)
        val scheme = if (serverProtocol == "wss") "https" else "http"
        val addr = "$scheme://$withPort"
        return if (addr.endsWith("/")) addr.dropLast(1) else addr
    }

    fun mediaUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("data:")) return path
        val b = baseUrl()
        return if (path.startsWith("/")) "$b$path" else "$b/$path"
    }

    /**
     * 安全解析响应体为 JSON，当服务端返回 404 空体/HTML 等时返回 {ok:false,error:"HTTP x ..."}，
     * 不会再抛出 `Value 404 of type java.lang.Integer cannot be converted to JSONObject`。
     */
    private fun parseJsonOrFallback(response: Response, body: String, defaultError: String): JSONObject {
        if (body.isBlank()) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "HTTP ${response.code} ${response.message.ifBlank { defaultError }}")
            }
        }
        return try {
            JSONObject(body)
        } catch (_: Throwable) {
            val snippet = body.replace(Regex("\\s+"), " ").take(80)
            JSONObject().apply {
                put("ok", false)
                put(
                    "error",
                    if (response.isSuccessful) "响应非 JSON：$snippet"
                    else "HTTP ${response.code}：$snippet"
                )
            }
        }
    }

    // ==================================================================
    //  注册 / 登录 / 配对码
    // ==================================================================

    suspend fun register(username: String, password: String, nickname: String): Result<User> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("username", username)
                put("password", password)
                put("nickname", nickname)
            }
            val request = Request.Builder()
                .url("${baseUrl()}/register")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "注册失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val u = obj.optJSONObject("user") ?: return@use Result.failure(Exception("响应缺少 user 字段"))
                    Result.success(parseUser(u))
                } else {
                    Result.failure(Exception(obj.optString("error", "注册失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun login(username: String, password: String): Result<Pair<String, User>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            val request = Request.Builder()
                .url("${baseUrl()}/login")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "登录失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val token = obj.optString("token", "")
                    val u = obj.optJSONObject("user")
                    if (token.isBlank() || u == null) return@use Result.failure(Exception("响应缺少 token 或 user"))
                    Result.success(token to parseUser(u))
                } else {
                    Result.failure(Exception(obj.optString("error", "登录失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 配对码登录。
     *
     * 服务端 pair.js loginWithPairCode 返回 {ok:true, token, user} 或 {ok:false, error}，
     * 这里严格按该结构解析。
     */
    suspend fun loginWithPairCode(pairCode: String): Result<Pair<String, User>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply { put("pairCode", pairCode) }
            val request = Request.Builder()
                .url("${baseUrl()}/api/pair/login")
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "配对码登录失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val token = obj.optString("token", "")
                    val u = obj.optJSONObject("user")
                    if (token.isBlank() || u == null) return@use Result.failure(Exception("响应缺少 token 或 user"))
                    Result.success(token to parseUser(u))
                } else {
                    Result.failure(Exception(obj.optString("error", "配对码无效或已过期")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class PairCodeInfo(
        val pairCode: String,
        val expiresAt: Long,
        val expiresIn: Int,
        val status: String
    )

    suspend fun generatePairCode(token: String): Result<PairCodeInfo> = authCall(token,
        build = { b ->
            b.url("${baseUrl()}/api/pair/generate").post("{}".toRequestBody(jsonMediaType))
        },
        defaultErr = "生成配对码失败"
    ) { _, obj ->
        PairCodeInfo(
            pairCode = obj.optString("pairCode"),
            expiresAt = obj.optLong("expiresAt", 0L),
            expiresIn = obj.optInt("expiresIn", 120),
            status = obj.optString("status", "active")
        )
    }

    suspend fun cancelPairCode(token: String): Result<Boolean> = authCall(token,
        build = { b ->
            b.url("${baseUrl()}/api/pair/cancel").post("{}".toRequestBody(jsonMediaType))
        },
        defaultErr = "取消配对码失败"
    ) { _, _ -> true }

    suspend fun getPairStatus(token: String): Result<PairCodeInfo?> = authCall(token,
        build = { b -> b.url("${baseUrl()}/api/pair/status").get() },
        defaultErr = "获取配对码状态失败"
    ) { _, obj ->
        val code = obj.optString("pairCode")
        if (code.isNullOrBlank()) null else PairCodeInfo(
            pairCode = code,
            expiresAt = obj.optLong("expiresAt", 0L),
            expiresIn = obj.optInt("expiresIn", 0),
            status = obj.optString("status", "active")
        )
    }

    // ==================================================================
    //  用户与搜索
    // ==================================================================

    data class SearchUserResult(
        val id: String,
        val username: String,
        val nickname: String,
        val avatar: String?,
        val avatarType: String?,
        val avatarEmoji: String?,
        val alreadyFriend: Boolean,
        val pendingRequest: Boolean
    )

    /** POST /search/users 搜索用户（需要登录） */
    suspend fun searchUsers(token: String, keyword: String): Result<List<SearchUserResult>> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("keyword", keyword) }
            b.url("${baseUrl()}/search/users").post(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "搜索用户失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("users") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val u = arr.getJSONObject(i)
            SearchUserResult(
                id = u.optString("id"),
                username = u.optString("username"),
                nickname = u.optString("nickname"),
                avatar = u.optString("avatar").takeUnless { it.isNullOrBlank() },
                avatarType = u.optString("avatarType").takeUnless { it.isNullOrBlank() },
                avatarEmoji = u.optString("avatarEmoji").takeUnless { it.isNullOrBlank() },
                alreadyFriend = u.optBoolean("alreadyFriend", false) ||
                        u.optBoolean("isFriend", false),
                pendingRequest = u.optBoolean("pendingRequest", false) ||
                        u.optBoolean("hasPending", false)
            )
        }
    }

    data class SearchGroupResult(
        val id: String,
        val groupNumber: String,
        val name: String,
        val description: String,
        val memberCount: Int,
        val hasPassword: Boolean,
        val joined: Boolean,
        val isOwner: Boolean
    )

    /** POST /search/groups 搜索群聊（需要登录） */
    suspend fun searchGroups(token: String, keyword: String): Result<List<SearchGroupResult>> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("keyword", keyword) }
            b.url("${baseUrl()}/search/groups").post(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "搜索群聊失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("groups") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            SearchGroupResult(
                id = g.optString("id"),
                groupNumber = g.optString("groupNumber"),
                name = g.optString("name"),
                description = g.optString("description"),
                memberCount = g.optInt("memberCount", 0),
                hasPassword = g.optBoolean("hasPassword", false),
                joined = g.optBoolean("joined", false),
                isOwner = g.optBoolean("isOwner", false)
            )
        }
    }

    // ==================================================================
    //  好友 / 好友请求 / 群聊列表 / 群成员（GET 集合类接口）
    // ==================================================================

    suspend fun getFriends(token: String): Result<List<Friend>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/friends").get() },
        defaultErr = "获取好友列表失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("friends") ?: JSONArray()
        (0 until arr.length()).map { i -> parseFriend(arr.getJSONObject(i)) }
    }

    suspend fun getFriendRequests(token: String): Result<List<FriendRequest>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/friend_requests").get() },
        defaultErr = "获取好友申请列表失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("requests") ?: JSONArray()
        (0 until arr.length()).map { i -> parseFriendRequest(arr.getJSONObject(i)) }
    }

    suspend fun getGroups(token: String): Result<List<GroupMembership>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/groups").get() },
        defaultErr = "获取群聊列表失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("groups") ?: JSONArray()
        (0 until arr.length()).map { i -> parseGroupMembership(arr.getJSONObject(i)) }
    }

    // ==================================================================
    //  历史消息
    // ==================================================================

    suspend fun getPrivateMessages(token: String, friendId: String): Result<List<ChatMessage>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/messages/private/${encode(friendId)}").get() },
        defaultErr = "加载私聊消息失败"
    ) { _, obj -> parseMessages(obj.optJSONArray("messages") ?: JSONArray()) }

    suspend fun getGroupMessages(token: String, groupId: String): Result<List<ChatMessage>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/messages/group/${encode(groupId)}").get() },
        defaultErr = "加载群聊消息失败"
    ) { _, obj -> parseMessages(obj.optJSONArray("messages") ?: JSONArray()) }

    // ==================================================================
    //  上传：图片 / 语音（multipart/form-data）
    // ==================================================================

    data class UploadedFile(val url: String, val durationSec: Int = 0)

    /** POST /upload/image  fieldname = image/file */
    suspend fun uploadImage(token: String, file: File): Result<UploadedFile> = withContext(Dispatchers.IO) {
        try {
            val reqBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "image",
                    filename = file.name,
                    body = file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("${baseUrl()}/upload/image")
                .header("Authorization", "Bearer $token")
                .post(reqBody)
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "图片上传失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val url = obj.optString("url") ?: return@use Result.failure(Exception("响应缺少 url"))
                    Result.success(UploadedFile(url = url))
                } else {
                    Result.failure(Exception(obj.optString("error", "图片上传失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /upload/audio  fieldname = audio/file，同时支持 duration 字段 */
    suspend fun uploadAudio(token: String, file: File, durationSec: Int): Result<UploadedFile> = withContext(Dispatchers.IO) {
        try {
            val reqBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("duration", durationSec.toString())
                .addFormDataPart(
                    name = "audio",
                    filename = file.name,
                    body = file.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("${baseUrl()}/upload/audio")
                .header("Authorization", "Bearer $token")
                .post(reqBody)
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "语音上传失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val url = obj.optString("url") ?: return@use Result.failure(Exception("响应缺少 url"))
                    val dur = obj.optInt("duration", durationSec)
                    Result.success(UploadedFile(url = url, durationSec = dur))
                } else {
                    Result.failure(Exception(obj.optString("error", "语音上传失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================================================================
    //  辅助：带 auth header 的统一调用封装
    // ==================================================================

    private suspend inline fun <T> authCall(
        token: String,
        crossinline build: (Request.Builder) -> Request.Builder,
        defaultErr: String,
        crossinline parseOk: (Response, JSONObject) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        try {
            val b = Request.Builder().header("Authorization", "Bearer $token")
            val request = build(b).build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, defaultErr)
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    Result.success(parseOk(response, obj))
                } else {
                    Result.failure(Exception(obj.optString("error", defaultErr)))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    // ==================================================================
    //  解析
    // ==================================================================

    private fun parseUser(json: JSONObject): User = User(
        id = json.optString("id"),
        username = json.optString("username", ""),
        nickname = json.optString("nickname", ""),
        avatar = json.optString("avatar").takeUnless { it.isNullOrBlank() },
        avatarType = json.optString("avatarType").takeUnless { it.isNullOrBlank() },
        avatarEmoji = json.optString("avatarEmoji").takeUnless { it.isNullOrBlank() },
        createdAt = json.optLong("createdAt")
    )

    private fun parseFriend(json: JSONObject): Friend = Friend(
        userId = json.optString("userId"),
        user = parseUser(json.getJSONObject("user")),
        since = json.optLong("since"),
        online = json.optBoolean("online", false)
    )

    private fun parseFriendRequest(json: JSONObject): FriendRequest = FriendRequest(
        id = json.optString("id"),
        fromId = json.optString("fromId"),
        toId = json.optString("toId"),
        message = json.optString("message", ""),
        status = json.optString("status", "pending"),
        createdAt = json.optLong("createdAt"),
        fromUser = json.optJSONObject("fromUser")?.let(::parseUser),
        toUser = json.optJSONObject("toUser")?.let(::parseUser)
    )

    private fun parseGroupMembership(json: JSONObject): GroupMembership {
        val g = json.getJSONObject("group")
        return GroupMembership(
            group = GroupInfo(
                id = g.optString("id"),
                groupNumber = g.optString("groupNumber"),
                name = g.optString("name"),
                description = g.optString("description", ""),
                ownerId = g.optString("ownerId"),
                hasPassword = g.optBoolean("hasPassword", false),
                createdAt = g.optLong("createdAt")
            ),
            role = json.optString("role", "member"),
            mutedUntil = json.optLong("mutedUntil"),
            joinedAt = json.optLong("joinedAt"),
            memberCount = json.optInt("memberCount", 0)
        )
    }

    private fun parseMessages(arr: JSONArray): List<ChatMessage> = (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        ChatMessage(
            id = m.optString("id"),
            type = m.optString("type"),
            fromId = m.optString("fromId"),
            fromNickname = m.optString("fromNickname", ""),
            toId = m.optString("toId"),
            groupId = m.optString("groupId"),
            contentType = m.optString("contentType", "text"),
            content = m.optString("content", ""),
            image = m.optString("image"),
            audio = m.optString("audio"),
            timestamp = m.optLong("timestamp")
        )
    }
}
