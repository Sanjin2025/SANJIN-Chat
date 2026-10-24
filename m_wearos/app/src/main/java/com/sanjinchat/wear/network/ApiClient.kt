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

    /** GET /api/user/me 获取当前登录用户资料（WS 未连上时的 fallback） */
    suspend fun getMyProfile(token: String): Result<User> = authCall(token,
        build = { b -> b.url("${baseUrl()}/api/user/me").get() },
        defaultErr = "获取用户资料失败"
    ) { _, obj ->
        val u = obj.getJSONObject("user")
        User(
            id = u.optString("id"),
            username = u.optString("username"),
            nickname = u.optString("nickname"),
            avatar = u.optString("avatar").takeUnless { it.isNullOrBlank() },
            avatarType = u.optString("avatarType").takeUnless { it.isNullOrBlank() },
            avatarEmoji = u.optString("avatarEmoji").takeUnless { it.isNullOrBlank() },
            role = u.optString("role").ifBlank { "user" },
            createdAt = u.optLong("createdAt")
        )
    }

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

    /** GET /api/messages/sync 云端同步：返回当前用户 10 天内参与的所有私聊/群聊消息 */
    suspend fun syncMessages(token: String): Result<List<ChatMessage>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/api/messages/sync").get() },
        defaultErr = "同步消息失败"
    ) { _, obj -> parseMessages(obj.optJSONArray("messages") ?: JSONArray()) }

    // ==================================================================
    //  上传：图片 / 语音（multipart/form-data）
    // ==================================================================

    data class UploadedFile(val url: String, val durationSec: Int = 0)

    /** POST /upload/image  fieldname = image/file
     *  服务端按 part 的 Content-Type 校验 MIME（image/jpeg|png|gif|webp|bmp），
     *  因此必须根据文件扩展名设置正确的 MediaType，否则会被拒收为 "不支持的文件类型"。
     */
    suspend fun uploadImage(token: String, file: File): Result<UploadedFile> = withContext(Dispatchers.IO) {
        try {
            val mime = guessImageMime(file.name)
            val reqBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "image",
                    filename = file.name,
                    body = file.asRequestBody(mime.toMediaType())
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
                    val url = obj.optString("url").ifBlank { return@use Result.failure(Exception("响应缺少 url")) }
                    Result.success(UploadedFile(url = url))
                } else {
                    Result.failure(Exception(obj.optString("error", "图片上传失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /upload/audio  fieldname = audio/file，同时支持 duration 字段
     *  服务端允许 audio/webm|ogg|mp4|mpeg|wav，必须按扩展名设置正确的 Content-Type。
     */
    suspend fun uploadAudio(token: String, file: File, durationSec: Int): Result<UploadedFile> = withContext(Dispatchers.IO) {
        try {
            val mime = guessAudioMime(file.name)
            val reqBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("duration", durationSec.toString())
                .addFormDataPart(
                    name = "audio",
                    filename = file.name,
                    body = file.asRequestBody(mime.toMediaType())
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
                    val url = obj.optString("url").ifBlank { return@use Result.failure(Exception("响应缺少 url")) }
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

    /** 根据文件扩展名推断图片 MIME（服务端校验使用，必须落入白名单） */
    private fun guessImageMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            else -> "image/jpeg" // 默认按 jpeg 上传，避免被服务端拒收
        }
    }

    /** 根据文件扩展名推断音频 MIME（服务端校验使用，必须落入白名单） */
    private fun guessAudioMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "webm" -> "audio/webm"
            "ogg" -> "audio/ogg"
            "m4a", "mp4" -> "audio/mp4"
            "mp3", "mpeg" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "audio/mp4"
        }
    }

    // ==================================================================
    //  个人资料：昵称 / Emoji 头像 / 图片头像 / 注销账号
    // ==================================================================

    /** PUT /user/nickname 改昵称（1-20 字符） */
    suspend fun updateNickname(token: String, nickname: String): Result<User> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("nickname", nickname) }
            b.url("${baseUrl()}/user/nickname").put(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "修改昵称失败"
    ) { _, obj -> parseUser(obj.getJSONObject("user")) }

    /** PUT /api/user/password 修改密码（需验证旧密码，新密码 6-32 位） */
    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Boolean> = authCall(token,
        build = { b ->
            val body = JSONObject().apply {
                put("oldPassword", oldPassword)
                put("newPassword", newPassword)
            }
            b.url("${baseUrl()}/api/user/password").put(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "修改密码失败"
    ) { _, _ -> true }

    /** PUT /api/user/avatar/emoji 设置 Emoji 头像（12 种白名单）
     *  服务端响应：{ ok, avatar: emoji }（不返回 user），客户端通过 currentUser 流获取最新值
     */
    suspend fun setAvatarEmoji(token: String, emoji: String): Result<String> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("emoji", emoji) }
            b.url("${baseUrl()}/api/user/avatar/emoji").put(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "设置头像失败"
    ) { _, obj -> obj.optString("avatar", emoji) }

    /** POST /api/user/avatar 上传图片头像（≤2MB，jpg/png/gif/webp/bmp）
     *  服务端响应：{ ok, url }（不返回 user），客户端通过 currentUser 流获取最新值
     */
    suspend fun uploadAvatarImage(token: String, file: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val mime = guessImageMime(file.name)
            val reqBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    name = "avatar",
                    filename = file.name,
                    body = file.asRequestBody(mime.toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("${baseUrl()}/api/user/avatar")
                .header("Authorization", "Bearer $token")
                .post(reqBody)
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "头像上传失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    Result.success(obj.optString("url"))
                } else {
                    Result.failure(Exception(obj.optString("error", "头像上传失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** DELETE /api/user 注销账号（需二次密码验证，永久删除）
     *  服务端响应：{ ok }
     */
    suspend fun deleteAccount(token: String, password: String): Result<Boolean> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("password", password) }
            b.url("${baseUrl()}/api/user").delete(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "注销账号失败"
    ) { _, _ -> true }

    // ==================================================================
    //  群成员列表
    // ==================================================================

    data class GroupMember(
        val userId: String,
        val role: String,
        val mutedUntil: Long,
        val joinedAt: Long,
        val user: User
    )

    /** GET /groups/:id/members 群成员列表（按角色排序：owner → admin → member）
     *  注意：服务端路径是 /groups/:id/members，不是 /group_members/:id
     */
    suspend fun getGroupMembers(token: String, groupId: String): Result<List<GroupMember>> = authCall(token,
        build = { b -> b.url("${baseUrl()}/groups/${encode(groupId)}/members").get() },
        defaultErr = "获取群成员失败"
    ) { _, obj ->
        val arr = obj.optJSONArray("members") ?: JSONArray()
        (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            GroupMember(
                userId = m.optString("userId"),
                role = m.optString("role", "member"),
                mutedUntil = m.optLong("mutedUntil"),
                joinedAt = m.optLong("joinedAt"),
                user = parseUser(m.getJSONObject("user"))
            )
        }
    }

    // ==================================================================
    //  公告
    // ==================================================================

    data class Announcement(
        val id: String,
        val title: String,
        val content: String,
        val type: String,        // info / warning / important
        val authorName: String,
        val createdAt: Long,
        val expiresAt: Long
    )

    /** GET /api/announcements 未过期公告列表（按创建时间倒序） */
    suspend fun getAnnouncements(token: String? = null): Result<List<Announcement>> = withContext(Dispatchers.IO) {
        try {
            val b = if (token != null) Request.Builder().header("Authorization", "Bearer $token")
                    else Request.Builder()
            val request = b.url("${baseUrl()}/api/announcements").get().build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "获取公告失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val arr = obj.optJSONArray("announcements") ?: JSONArray()
                    val list = (0 until arr.length()).map { i ->
                        val a = arr.getJSONObject(i)
                        Announcement(
                            id = a.optString("id"),
                            title = a.optString("title"),
                            content = a.optString("content", ""),
                            type = a.optString("type", "info"),
                            authorName = a.optString("authorName")
                                .takeUnless { it.isBlank() }
                                ?: a.optString("authorNickname", "管理员"),
                            createdAt = a.optLong("createdAt"),
                            expiresAt = a.optLong("expiresAt", 0L)
                        )
                    }
                    Result.success(list)
                } else {
                    Result.failure(Exception(obj.optString("error", "获取公告失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/announcements 新建公告（管理员） */
    suspend fun createAnnouncement(
        token: String,
        title: String,
        content: String,
        type: String,
        expiresAt: Long = 0L
    ): Result<Announcement> = authCall(token,
        build = { b ->
            val body = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("type", type)
                if (expiresAt > 0L) put("expiresAt", expiresAt)
            }
            b.url("${baseUrl()}/api/announcements").post(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "创建公告失败"
    ) { _, obj ->
        val a = obj.getJSONObject("announcement")
        Announcement(
            id = a.optString("id"),
            title = a.optString("title"),
            content = a.optString("content", ""),
            type = a.optString("type", "info"),
            authorName = a.optString("authorName").ifBlank { a.optString("authorNickname", "管理员") },
            createdAt = a.optLong("createdAt"),
            expiresAt = a.optLong("expiresAt", 0L)
        )
    }

    /** PUT /api/announcements/:id 修改公告（管理员） */
    suspend fun updateAnnouncement(
        token: String,
        id: String,
        title: String,
        content: String,
        type: String,
        expiresAt: Long = 0L
    ): Result<Announcement> = authCall(token,
        build = { b ->
            val body = JSONObject().apply {
                put("title", title)
                put("content", content)
                put("type", type)
                if (expiresAt > 0L) put("expiresAt", expiresAt)
            }
            b.url("${baseUrl()}/api/announcements/${encode(id)}").put(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "修改公告失败"
    ) { _, obj ->
        val a = obj.getJSONObject("announcement")
        Announcement(
            id = a.optString("id"),
            title = a.optString("title"),
            content = a.optString("content", ""),
            type = a.optString("type", "info"),
            authorName = a.optString("authorName").ifBlank { a.optString("authorNickname", "管理员") },
            createdAt = a.optLong("createdAt"),
            expiresAt = a.optLong("expiresAt", 0L)
        )
    }

    /** DELETE /api/announcements/:id 删除公告（管理员） */
    suspend fun deleteAnnouncement(token: String, id: String): Result<Boolean> = authCall(token,
        build = { b -> b.url("${baseUrl()}/api/announcements/${encode(id)}").delete() },
        defaultErr = "删除公告失败"
    ) { _, _ -> true }

    // ==================================================================
    //  服务器信息
    // ==================================================================

    data class ServerInfo(
        val serverName: String,
        val serverDescription: String,
        val serverAnnouncement: String,
        val copyright: String,
        val logo: String?,
        val logoType: String,        // "emoji" or "image"
        val logoEmoji: String?,
        val totalUsers: Int,
        val totalGroups: Int,
        val uptimeSeconds: Long,
        val isAdmin: Boolean,
        // 新增：服务器版本 / 协议 / 地址
        val serverVersion: String,
        val serverProtocol: String,
        val serverAddress: String
    )

    /** GET /api/server/info 服务器公开信息（含统计） */
    suspend fun getServerInfo(token: String? = null): Result<ServerInfo> = withContext(Dispatchers.IO) {
        try {
            val b = if (token != null) Request.Builder().header("Authorization", "Bearer $token")
                    else Request.Builder()
            val request = b.url("${baseUrl()}/api/server/info").get().build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "获取服务器信息失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    Result.success(ServerInfo(
                        serverName = obj.optString("serverName", "SanjinChat"),
                        serverDescription = obj.optString("serverDescription", ""),
                        serverAnnouncement = obj.optString("serverAnnouncement", ""),
                        copyright = obj.optString("copyright", ""),
                        logo = obj.optString("logo").takeUnless { it.isNullOrBlank() },
                        logoType = obj.optString("logoType", "emoji"),
                        logoEmoji = obj.optString("logoEmoji").takeUnless { it.isNullOrBlank() },
                        totalUsers = obj.optInt("totalUsers", 0),
                        totalGroups = obj.optInt("totalGroups", 0),
                        uptimeSeconds = obj.optLong("uptimeSeconds", 0L),
                        isAdmin = obj.optBoolean("isAdmin", false),
                        serverVersion = obj.optString("serverVersion", ""),
                        serverProtocol = obj.optString("serverProtocol", "ws"),
                        serverAddress = obj.optString("serverAddress", "")
                    ))
                } else {
                    Result.failure(Exception(obj.optString("error", "获取服务器信息失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==================================================================
    //  扫码登录全流程
    //  GET /api/qrcode/generate        生成二维码会话+图片（DataURL）
    //  GET  /api/qrcode/status/:id     轮询状态 pending/confirmed/expired
    //  POST /api/qrcode/consume/:id    确认后取 token
    //  POST /api/qrcode/confirm        已登录端扫码确认
    // ==================================================================

    data class QrSession(
        val qrSessionId: String,      // 与服务端字段一致
        val qrDataUrl: String,        // data:image/png;base64,xxx
        val expiresAt: Long
    )

    /** 二维码轮询结果（服务端接口 /api/qrcode/status/:id 在 confirmed 时会直接返回 token+user，无需再消费） */
    sealed class QrPoll {
        object Pending : QrPoll()
        object Expired : QrPoll()
        data class Confirmed(val token: String, val user: User) : QrPoll()
    }

    /** GET /api/qrcode/generate 生成二维码会话（无需登录） */
    suspend fun generateQrCode(): Result<QrSession> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/qrcode/generate")
                .get()
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "生成二维码失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    val sid = obj.optString("qrSessionId")
                        .takeUnless { it.isBlank() } ?: obj.optString("sessionId")
                    if (sid.isBlank()) return@use Result.failure(Exception("服务端未返回 qrSessionId"))
                    Result.success(
                        QrSession(
                            qrSessionId = sid,
                            qrDataUrl = obj.optString("qrDataUrl"),
                            expiresAt = obj.optLong("expiresAt", 0L)
                        )
                    )
                } else {
                    Result.failure(Exception(obj.optString("error", "生成二维码失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * GET /api/qrcode/status/:id 轮询二维码状态
     * 服务端：
     *  - pending / expired  => {ok:true, status:"pending"|"expired"}
     *  - confirmed          => {ok:true, status:"confirmed", token:"...", user:{...}}
     */
    suspend fun getQrStatus(sessionId: String): Result<QrPoll> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/api/qrcode/status/${encode(sessionId)}")
                .get()
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "查询二维码状态失败")
                if (!response.isSuccessful || !obj.optBoolean("ok")) {
                    return@use Result.failure(Exception(obj.optString("error", "查询二维码状态失败")))
                }
                when (val s = obj.optString("status", "pending")) {
                    "expired" -> Result.success(QrPoll.Expired)
                    "confirmed" -> {
                        val token = obj.optString("token", "")
                        val u = obj.optJSONObject("user")
                        if (token.isBlank() || u == null)
                            Result.failure(Exception("二维码已确认但缺少 token"))
                        else
                            Result.success(QrPoll.Confirmed(token, parseUser(u)))
                    }
                    else -> Result.success(QrPoll.Pending)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** POST /api/qrcode/confirm 已登录端扫码确认（扫描另一台设备的二维码后调用） */
    suspend fun confirmQrCode(token: String, sessionId: String): Result<Boolean> = authCall(token,
        build = { b ->
            val body = JSONObject().apply { put("qrSessionId", sessionId) }
            b.url("${baseUrl()}/api/qrcode/confirm").post(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "确认二维码失败"
    ) { _, _ -> true }

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
        role = json.optString("role", "user"),
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
            toId = m.optString("toId").ifBlank { null },
            groupId = m.optString("groupId").ifBlank { null },
            contentType = m.optString("contentType", "text"),
            content = m.optString("content", ""),
            image = m.optString("image").ifBlank { null },
            audio = m.optString("audio").ifBlank { null },
            timestamp = m.optLong("timestamp")
        )
    }

    // ==================================================================
    //  一次性验证码 (OTP) 登录
    //  POST /api/otp/generate   username => {ok:true, code, expiresAt, expiresIn}
    //  POST /api/otp/login      {username, code} => {ok:true, token, user}
    // ==================================================================

    data class OtpInfo(
        val code: String,
        val expiresAt: Long,
        val expiresIn: Int
    )

    suspend fun generateOtp(username: String): Result<OtpInfo> = withContext(Dispatchers.IO) {
        try {
            val u = username.trim()
            if (u.isBlank()) return@withContext Result.failure(Exception("用户名不能为空"))
            val body = JSONObject().apply { put("username", u) }
            val request = Request.Builder()
                .url("${baseUrl()}/api/otp/generate")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()
            client().newCall(request).execute().use { response ->
                val rb = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, rb, "生成验证码失败")
                if (response.isSuccessful && obj.optBoolean("ok")) {
                    Result.success(
                        OtpInfo(
                            code = obj.optString("code"),
                            expiresAt = obj.optLong("expiresAt", 0L),
                            expiresIn = obj.optInt("expiresIn", 300)
                        )
                    )
                } else {
                    Result.failure(Exception(obj.optString("error", "生成验证码失败")))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loginWithOtp(username: String, code: String): Result<Pair<String, User>> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("username", username.trim())
                    put("code", code.trim())
                }
                val request = Request.Builder()
                    .url("${baseUrl()}/api/otp/login")
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()
                client().newCall(request).execute().use { response ->
                    val rb = response.body?.string() ?: ""
                    val obj = parseJsonOrFallback(response, rb, "验证码登录失败")
                    if (response.isSuccessful && obj.optBoolean("ok")) {
                        val token = obj.optString("token", "")
                        val u = obj.optJSONObject("user")
                        if (token.isBlank() || u == null)
                            return@use Result.failure(Exception("响应缺少 token 或 user"))
                        Result.success(token to parseUser(u))
                    } else {
                        Result.failure(Exception(obj.optString("error", "验证码无效或已过期")))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ==================================================================
    //  曝光板 API（每条资料 10h 限时曝光）
    //  GET  /api/exposure/list                 公开列表（自动过滤已过期）
    //  GET  /api/exposure/mine                 我自己的曝光状态（Auth）
    //  POST /api/exposure/upsert               创建/更新/续期（Auth）
    //  POST /api/exposure/leave                下架（Auth）
    // ==================================================================

    data class ExposureItem(
        val id: String,
        val userId: String,
        val username: String,
        val nickname: String,
        val avatar: String?,
        val avatarType: String?,
        val avatarEmoji: String?,
        val bio: String,
        val tags: List<String>,
        val expiresAt: Long
    )

    data class MyExposure(
        val onBoard: Boolean,
        val id: String?,
        val bio: String?,
        val tags: List<String>,
        val visible: Boolean,
        val expiresAt: Long,
        val expired: Boolean,
        val expiresInMs: Long
    )

    data class ExposureList(
        val total: Int,
        val page: Int,
        val pageSize: Int,
        val list: List<ExposureItem>
    )

    suspend fun getExposureList(page: Int = 1, pageSize: Int = 20): Result<ExposureList> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("${baseUrl()}/api/exposure/list?page=$page&pageSize=$pageSize")
                    .get()
                    .build()
                client().newCall(request).execute().use { response ->
                    val rb = response.body?.string() ?: ""
                    val obj = parseJsonOrFallback(response, rb, "获取曝光板失败")
                    if (!response.isSuccessful || !obj.optBoolean("ok"))
                        return@use Result.failure(Exception(obj.optString("error", "获取曝光板失败")))
                    val arr = obj.optJSONArray("list")
                    val list: MutableList<ExposureItem> = mutableListOf()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val e = arr.optJSONObject(i) ?: continue
                            val tagsJ = e.optJSONArray("tags")
                            val tags = (0 until (tagsJ?.length() ?: 0)).mapNotNull { tagsJ?.optString(it) }
                            list.add(
                                ExposureItem(
                                    id = e.optString("id"),
                                    userId = e.optString("userId"),
                                    username = e.optString("username"),
                                    nickname = e.optString("nickname"),
                                    avatar = e.optString("avatar").takeUnless { it.isNullOrBlank() },
                                    avatarType = e.optString("avatarType").takeUnless { it.isNullOrBlank() },
                                    avatarEmoji = e.optString("avatarEmoji").takeUnless { it.isNullOrBlank() },
                                    bio = e.optString("bio", ""),
                                    tags = tags,
                                    expiresAt = e.optLong("expiresAt", 0L)
                                )
                            )
                        }
                    }
                    Result.success(
                        ExposureList(
                            total = obj.optInt("total", list.size),
                            page = obj.optInt("page", page),
                            pageSize = obj.optInt("pageSize", pageSize),
                            list = list
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getMyExposure(token: String): Result<MyExposure> = authCall(token,
        build = { b -> b.url("${baseUrl()}/api/exposure/mine").get() },
        defaultErr = "获取我的曝光失败"
    ) { _, obj ->
        val tagsJ = obj.optJSONArray("tags")
        val tags = (0 until (tagsJ?.length() ?: 0)).mapNotNull { tagsJ?.optString(it) }
        MyExposure(
            onBoard = obj.optBoolean("onBoard", false),
            id = obj.optString("id").takeUnless { it.isBlank() },
            bio = obj.optString("bio").takeUnless { it.isNullOrBlank() },
            tags = tags,
            visible = obj.optBoolean("visible", true),
            expiresAt = obj.optLong("expiresAt", 0L),
            expired = obj.optBoolean("expired", false),
            expiresInMs = obj.optLong("expiresInMs", 0L)
        )
    }

    suspend fun upsertExposure(
        token: String,
        bio: String,
        tags: List<String>
    ): Result<MyExposure> = authCall(token,
        build = { b ->
            val body = JSONObject().apply {
                put("bio", bio)
                put("tags", JSONArray().also { arr -> tags.forEach { arr.put(it) } })
            }
            b.url("${baseUrl()}/api/exposure/upsert")
                .post(body.toString().toRequestBody(jsonMediaType))
        },
        defaultErr = "更新曝光失败"
    ) { _, obj ->
        val tagsJ = obj.optJSONArray("tags")
        val t = (0 until (tagsJ?.length() ?: 0)).mapNotNull { tagsJ?.optString(it) }
        MyExposure(
            onBoard = obj.optBoolean("onBoard", true),
            id = obj.optString("id").takeUnless { it.isBlank() },
            bio = obj.optString("bio").takeUnless { it.isNullOrBlank() },
            tags = t,
            visible = obj.optBoolean("visible", true),
            expiresAt = obj.optLong("expiresAt", 0L),
            expired = false,
            expiresInMs = obj.optLong("expiresInMs", 0L)
        )
    }

    suspend fun leaveExposure(token: String): Result<Boolean> = authCall(token,
        build = { b ->
            b.url("${baseUrl()}/api/exposure/leave")
                .post("{}".toRequestBody(jsonMediaType))
        },
        defaultErr = "下架失败"
    ) { _, _ -> true }

    // ==================================================================
    //  应用内更新
    //  GET /api/update/check?channel=stable
    //  自定义通道：直接拉取用户填写的 version.json URL
    // ==================================================================

    data class AppUpdate(
        val hasUpdate: Boolean,
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,        // 完整可下载 URL
        val changelog: String,
        val channel: String,
        val forceUpdate: Boolean,
        val minSdk: Int
    )

    /**
     * 检查更新。
     * @param channel stable / beta / 自定义 http(s) URL（指向 version.json）
     * @param currentVersionCode 当前应用的 versionCode
     */
    suspend fun checkUpdate(channel: String, currentVersionCode: Int): Result<AppUpdate> = withContext(Dispatchers.IO) {
        try {
            val ch = channel.trim()
            // 自定义通道：直接拉取用户填写的 version.json
            if (ch.startsWith("http://") || ch.startsWith("https://")) {
                val request = Request.Builder().url(ch).get().build()
                client().newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) return@use Result.failure(Exception("HTTP ${response.code}"))
                    val obj = JSONObject(body)
                    val info = parseUpdateJson(obj, currentVersionCode, baseUrl())
                    return@use Result.success(info)
                }
            }

            // 标准通道：走服务端 /api/update/check
            val request = Request.Builder()
                .url("${baseUrl()}/api/update/check?channel=${encode(ch)}")
                .get()
                .build()
            client().newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                val obj = parseJsonOrFallback(response, body, "检查更新失败")
                if (!response.isSuccessful || !obj.optBoolean("ok")) {
                    return@use Result.failure(Exception(obj.optString("error", "检查更新失败")))
                }
                // 服务端没有版本文件
                if (!obj.optBoolean("hasUpdate")) {
                    return@use Result.success(AppUpdate(
                        hasUpdate = false, versionCode = 0, versionName = "",
                        apkUrl = "", changelog = "", channel = ch, forceUpdate = false, minSdk = 0
                    ))
                }
                // 服务端返回自定义 URL（自定义通道在服务端被透传）
                val customUrl = obj.optString("customUrl")
                if (customUrl.isNotBlank() && (customUrl.startsWith("http://") || customUrl.startsWith("https://"))) {
                    val req2 = Request.Builder().url(customUrl).get().build()
                    client().newCall(req2).execute().use { resp2 ->
                        val body2 = resp2.body?.string() ?: ""
                        if (!resp2.isSuccessful) return@use Result.failure(Exception("自定义通道 HTTP ${resp2.code}"))
                        val obj2 = JSONObject(body2)
                        return@use Result.success(parseUpdateJson(obj2, currentVersionCode, baseUrl()))
                    }
                }
                // 服务端直接返回版本信息
                val info = parseUpdateJson(obj, currentVersionCode, baseUrl())
                Result.success(info)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUpdateJson(obj: JSONObject, currentVersionCode: Int, baseUrl: String): AppUpdate {
        val serverCode = obj.optInt("versionCode", 0)
        val apkUrl = obj.optString("apkUrl", "").let { u ->
            if (u.startsWith("http://") || u.startsWith("https://")) u
            else if (u.startsWith("/")) "$baseUrl$u"
            else "$baseUrl/$u"
        }
        return AppUpdate(
            hasUpdate = serverCode > currentVersionCode,
            versionCode = serverCode,
            versionName = obj.optString("versionName", serverCode.toString()),
            apkUrl = apkUrl,
            changelog = obj.optString("changelog", ""),
            channel = obj.optString("channel", ""),
            forceUpdate = obj.optBoolean("forceUpdate", false),
            minSdk = obj.optInt("minSdk", 0)
        )
    }

    /**
     * 下载 APK 到外部缓存目录并返回文件。
     * @param onProgress 下载进度回调（0-100）
     */
    suspend fun downloadApk(apkUrl: String, onProgress: (Int) -> Unit = {}): Result<File> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(apkUrl).get().build()
            client().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use Result.failure(Exception("下载失败 HTTP ${response.code}"))
                val body = response.body ?: return@use Result.failure(Exception("响应体为空"))
                val contentLength = body.contentLength()
                val dir = context.externalCacheDir ?: context.cacheDir
                val apkFile = File(dir, "sanjinchat-update.apk")
                if (apkFile.exists()) apkFile.delete()

                var downloaded = 0L
                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (contentLength > 0) {
                                val pct = (downloaded * 100 / contentLength).toInt()
                                onProgress(pct)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(100)
                Result.success(apkFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 拉起系统安装器安装指定 APK 文件。
     * 通过 FileProvider 生成 content:// URI，适配 Android 7.0+。
     */
    fun installApk(apkFile: File) {
        try {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("ApiClient", "安装 APK 失败: ${e.message}")
        }
    }
}
