package com.sanjinchat.wear.network

import com.sanjinchat.wear.model.ChatMessage
import com.sanjinchat.wear.model.Friend
import com.sanjinchat.wear.model.FriendRequest
import com.sanjinchat.wear.model.GroupInfo
import com.sanjinchat.wear.model.GroupMembership
import com.sanjinchat.wear.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    data class Connected(val user: User) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class ChatEvent {
    data class Connected(val user: User) : ChatEvent()
    data class PrivateMessage(val message: ChatMessage) : ChatEvent()
    data class GroupMessage(val message: ChatMessage) : ChatEvent()
    data class FriendsUpdate(val friends: List<Friend>) : ChatEvent()
    data class GroupsUpdate(val groups: List<GroupMembership>) : ChatEvent()
    data class FriendRequestsUpdate(val requests: List<FriendRequest>) : ChatEvent()
    data class PresenceUpdate(val statuses: Map<String, String>) : ChatEvent()
    data class Error(val message: String) : ChatEvent()
}

class ChatWebSocketClient {

    private var _client: OkHttpClient? = null
    private var _clientProtocol: String? = null

    private fun client(): OkHttpClient {
        val p = serverProtocol
        val cached = _client
        if (cached != null && _clientProtocol == p) return cached
        val fresh = buildOkHttpClient(protocol = p, forWs = true)
        _client = fresh
        _clientProtocol = p
        return fresh
    }

    private var webSocket: WebSocket? = null
    // 注意：scope 不能因 Activity onDestroy 而 cancel。
    // 从任务管理器划掉后进程常存活，重开时 webSocketClient 会被复用；
    // 若 scope 被取消，connect() 里的 watchdog/timeout 以及 handleMessage 的 scope.launch 都不会执行，
    // 导致 connectionState 永远卡在 Connecting、登录超时。
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    /** 若协程作用域已被取消（例如被错误地 destroy 过），重建一个新的，保证 connect 能正常工作。 */
    private fun ensureScope() {
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
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
                // 协议变化：重置 client，以便重连时使用新配置的 TLS 宽容模式
                _client = null
                _clientProtocol = null
            }
        }

    var currentToken: String? = null
        private set

    fun isManualDisconnect(): Boolean = manualDisconnect

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser.asStateFlow()

    private val _friends = MutableStateFlow<List<Friend>>(emptyList())
    val friends: StateFlow<List<Friend>> = _friends.asStateFlow()

    private val _groups = MutableStateFlow<List<GroupMembership>>(emptyList())
    val groups: StateFlow<List<GroupMembership>> = _groups.asStateFlow()

    private val _friendRequests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val friendRequests: StateFlow<List<FriendRequest>> = _friendRequests.asStateFlow()

    // 允许 MainScreen 在 WS 推送前，先通过 HTTP 拉取结果直接填充列表
    fun setFriendsSnapshot(list: List<Friend>) { _friends.value = list }
    fun setGroupsSnapshot(list: List<GroupMembership>) { _groups.value = list }
    fun setFriendRequestsSnapshot(list: List<FriendRequest>) { _friendRequests.value = list }
    /** HTTP fallback：WS 未连上时也能设置当前用户，供 ChatScreen 判断消息归属 */
    fun setCurrentUser(user: User) {
        if (_currentUser.value == null) _currentUser.value = user
    }

    private var manualDisconnect = false
    private var connectingTimeoutJob: Job? = null
    private var connectionWatchdogJob: Job? = null

    fun connect(token: String) {
        ensureScope()
        currentToken = token
        manualDisconnect = false
        reconnectJob?.cancel()
        connectingTimeoutJob?.cancel()
        connectionWatchdogJob?.cancel()
        // 强制关闭半死不活的旧 socket（防止 doConnect 因为 webSocket!=null 而放弃重连）
        val oldWs = webSocket
        if (oldWs != null) {
            webSocket = null
            runCatching { oldWs.close(1001, "reconnect") }
        }
        // 二次看门狗：12s 内仍没进入 Connected → 认为状态卡死，强制断开+重连
        connectionWatchdogJob = scope.launch {
            delay(12_000)
            if (_connectionState.value is ConnectionState.Connecting) {
                val wsNow = webSocket
                webSocket = null
                runCatching { wsNow?.close(1001, "watchdog force reconnect") }
                val err = "连接状态卡死，强制重连…"
                _connectionState.value = ConnectionState.Error(err)
                runCatching { _events.emit(ChatEvent.Error(err)) }
                if (!manualDisconnect) scheduleReconnect()
            }
        }
        doConnect(token)
    }

    private fun doConnect(token: String) {
        if (webSocket != null) return

        _connectionState.value = ConnectionState.Connecting

        val wsUrl = buildWsUrl(token)
        val request = Request.Builder().url(wsUrl).build()

        // 10s 握手超时：服务器未在 10s 内返回 connected 包 → 强制判失败 + 重连
        connectingTimeoutJob?.cancel()
        connectingTimeoutJob = scope.launch {
            delay(10_000)
            if (_connectionState.value is ConnectionState.Connecting) {
                val wsNow = webSocket
                webSocket = null
                runCatching { wsNow?.close(1001, "handshake timeout") }
                val err = "连接握手超时，正在重连…"
                _connectionState.value = ConnectionState.Error(err)
                runCatching { _events.emit(ChatEvent.Error(err)) }
                if (!manualDisconnect) scheduleReconnect()
            }
        }

        webSocket = client().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                // onOpen 仅代表 TCP+TLS+WS 握手完成，真正“已连接”要等服务端的 connected 包
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                cleanupSocket(ws)
                if (!manualDisconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                cleanupSocket(ws)
                connectingTimeoutJob?.cancel()
                scope.launch {
                    _connectionState.value = ConnectionState.Error(t.message ?: "连接失败")
                    runCatching { _events.emit(ChatEvent.Error(t.message ?: "连接失败")) }
                }
                if (!manualDisconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun buildWsUrl(token: String): String {
        val cleaned = serverAddress.trim().removeSuffix("/")
        val withoutScheme = ServerEndpoint.stripScheme(cleaned)
        val withPort = ServerEndpoint.normalizeHostPort(withoutScheme)
        val scheme = if (serverProtocol == "wss") "wss" else "ws"
        val base = "$scheme://$withPort"
        val baseClean = if (base.endsWith("/")) base.dropLast(1) else base
        return "$baseClean/ws?token=$token"
    }

    /**
     * 清理 socket。只有当回调对应的 ws 就是当前 webSocket 时才清空，
     * 避免旧 socket 的 onClosed/onFailure 回调把新 connect 刚建立的 socket 误清掉。
     */
    private fun cleanupSocket(ws: WebSocket? = null) {
        if (ws == null || ws === webSocket) {
            webSocket = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000)
            currentToken?.let { token ->
                if (!manualDisconnect) {
                    doConnect(token)
                }
            }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        connectingTimeoutJob?.cancel()
        connectionWatchdogJob?.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        currentToken = null
        _connectionState.value = ConnectionState.Disconnected
        _currentUser.value = null
        _friends.value = emptyList()
        _groups.value = emptyList()
        _friendRequests.value = emptyList()
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            scope.launch {
                when (type) {
                    "connected" -> {
                        connectingTimeoutJob?.cancel()
                        connectionWatchdogJob?.cancel()
                        val user = parseUser(json.getJSONObject("user"))
                        _currentUser.value = user
                        _connectionState.value = ConnectionState.Connected(user)
                        runCatching { _events.emit(ChatEvent.Connected(user)) }
                    }
                    "private_message" -> {
                        val msg = parseChatMessage(json.getJSONObject("message"))
                        runCatching { _events.emit(ChatEvent.PrivateMessage(msg)) }
                    }
                    "group_message" -> {
                        val msg = parseChatMessage(json.getJSONObject("message"))
                        runCatching { _events.emit(ChatEvent.GroupMessage(msg)) }
                    }
                    "friends:update" -> {
                        val list = parseFriends(json.getJSONArray("friends"))
                        _friends.value = list
                        runCatching { _events.emit(ChatEvent.FriendsUpdate(list)) }
                    }
                    "groups:update" -> {
                        val list = parseGroups(json.getJSONArray("groups"))
                        _groups.value = list
                        runCatching { _events.emit(ChatEvent.GroupsUpdate(list)) }
                    }
                    "friend_requests:update" -> {
                        val list = parseFriendRequests(json.getJSONArray("requests"))
                        _friendRequests.value = list
                        runCatching { _events.emit(ChatEvent.FriendRequestsUpdate(list)) }
                    }
                    "presence" -> {
                        val statuses = parsePresence(json.getJSONObject("statuses"))
                        updateFriendsPresence(statuses)
                        runCatching { _events.emit(ChatEvent.PresenceUpdate(statuses)) }
                    }
                    "error" -> {
                        val msg = json.optString("message", "未知错误")
                        _connectionState.value = ConnectionState.Error(msg)
                        runCatching { _events.emit(ChatEvent.Error(msg)) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateFriendsPresence(statuses: Map<String, String>) {
        val updated = _friends.value.map { friend ->
            statuses[friend.userId]?.let { status ->
                friend.copy(online = status == "online")
            } ?: friend
        }
        if (updated != _friends.value) {
            _friends.value = updated
        }
    }

    fun sendPrivateMessage(toId: String, content: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "private_message")
            put("toId", toId)
            put("contentType", "text")
            put("content", content)
        }
        return send(msg.toString())
    }

    fun sendPrivateImage(toId: String, imageUrl: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "private_message")
            put("toId", toId)
            put("contentType", "image")
            put("content", "[图片]")
            put("image", imageUrl)
        }
        return send(msg.toString())
    }

    fun sendPrivateAudio(toId: String, audioUrl: String, durationSec: Int): Boolean {
        val audio = JSONObject().apply {
            put("url", audioUrl)
            put("duration", durationSec)
        }
        val msg = JSONObject().apply {
            put("type", "private_message")
            put("toId", toId)
            put("contentType", "audio")
            put("content", "[语音 ${durationSec}s]")
            put("audio", audio.toString())
        }
        return send(msg.toString())
    }

    fun sendGroupMessage(groupId: String, content: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group_message")
            put("groupId", groupId)
            put("contentType", "text")
            put("content", content)
        }
        return send(msg.toString())
    }

    fun sendGroupImage(groupId: String, imageUrl: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group_message")
            put("groupId", groupId)
            put("contentType", "image")
            put("content", "[图片]")
            put("image", imageUrl)
        }
        return send(msg.toString())
    }

    fun sendGroupAudio(groupId: String, audioUrl: String, durationSec: Int): Boolean {
        val audio = JSONObject().apply {
            put("url", audioUrl)
            put("duration", durationSec)
        }
        val msg = JSONObject().apply {
            put("type", "group_message")
            put("groupId", groupId)
            put("contentType", "audio")
            put("content", "[语音 ${durationSec}s]")
            put("audio", audio.toString())
        }
        return send(msg.toString())
    }

    fun sendFriendRequest(toId: String, message: String = ""): Boolean {
        val msg = JSONObject().apply {
            put("type", "friend_request:send")
            put("toId", toId)
            put("message", message)
        }
        return send(msg.toString())
    }

    fun acceptFriendRequest(requestId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "friend_request:accept")
            put("requestId", requestId)
        }
        return send(msg.toString())
    }

    fun rejectFriendRequest(requestId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "friend_request:reject")
            put("requestId", requestId)
        }
        return send(msg.toString())
    }

    fun removeFriend(friendId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "friend:remove")
            put("friendId", friendId)
        }
        return send(msg.toString())
    }

    fun createGroup(name: String, description: String = "", password: String? = null): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:create")
            put("name", name)
            put("description", description)
            password?.let { put("password", it) }
        }
        return send(msg.toString())
    }

    fun joinGroup(groupNumber: String, password: String = ""): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:join")
            put("groupNumber", groupNumber)
            if (password.isNotEmpty()) put("password", password)
        }
        return send(msg.toString())
    }

    fun leaveGroup(groupId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:leave")
            put("groupId", groupId)
        }
        return send(msg.toString())
    }

    // ===== 群管理：解散 / 踢人 / 禁言 / 解禁 / 设管理员 / 撤管理员 =====

    /** 解散群（仅群主） */
    fun dismissGroup(groupId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:dismiss")
            put("groupId", groupId)
        }
        return send(msg.toString())
    }

    /** 踢人（群主 / 管理员；管理员不能踢群主和其他管理员） */
    fun kickGroupMember(groupId: String, userId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:kick")
            put("groupId", groupId)
            put("userId", userId)
        }
        return send(msg.toString())
    }

    /** 禁言（群主 / 管理员；mutedUntil 为截止时间戳，0 表示解禁） */
    fun muteGroupMember(groupId: String, userId: String, mutedUntil: Long): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:mute")
            put("groupId", groupId)
            put("userId", userId)
            put("mutedUntil", mutedUntil)
        }
        return send(msg.toString())
    }

    /** 解除禁言（内部走 mute 接口传 mutedUntil=0） */
    fun unmuteGroupMember(groupId: String, userId: String): Boolean =
        muteGroupMember(groupId, userId, 0L)

    /** 设为管理员（仅群主） */
    fun setGroupAdmin(groupId: String, userId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:set_admin")
            put("groupId", groupId)
            put("userId", userId)
        }
        return send(msg.toString())
    }

    /** 撤销管理员（仅群主） */
    fun removeGroupAdmin(groupId: String, userId: String): Boolean {
        val msg = JSONObject().apply {
            put("type", "group:remove_admin")
            put("groupId", groupId)
            put("userId", userId)
        }
        return send(msg.toString())
    }

    private fun send(text: String): Boolean {
        val ws = webSocket ?: return false
        return try {
            ws.send(text)
        } catch (e: Exception) {
            false
        }
    }

    private fun parseUser(json: JSONObject): User {
        return User(
            id = json.getString("id"),
            username = json.optString("username", ""),
            nickname = json.optString("nickname", ""),
            avatar = json.optString("avatar"),
            avatarType = json.optString("avatarType"),
            avatarEmoji = json.optString("avatarEmoji"),
            role = json.optString("role", "user"),
            createdAt = json.optLong("createdAt")
        )
    }

    private fun parseFriends(jsonArr: JSONArray): List<Friend> {
        val list = mutableListOf<Friend>()
        for (i in 0 until jsonArr.length()) {
            val json = jsonArr.getJSONObject(i)
            list.add(
                Friend(
                    userId = json.getString("userId"),
                    user = parseUser(json.getJSONObject("user")),
                    since = json.optLong("since"),
                    online = json.optBoolean("online", false)
                )
            )
        }
        return list
    }

    private fun parseGroups(jsonArr: JSONArray): List<GroupMembership> {
        val list = mutableListOf<GroupMembership>()
        for (i in 0 until jsonArr.length()) {
            val json = jsonArr.getJSONObject(i)
            val gJson = json.getJSONObject("group")
            list.add(
                GroupMembership(
                    group = GroupInfo(
                        id = gJson.getString("id"),
                        groupNumber = gJson.getString("groupNumber"),
                        name = gJson.getString("name"),
                        description = gJson.optString("description", ""),
                        ownerId = gJson.getString("ownerId"),
                        hasPassword = gJson.optBoolean("hasPassword", false),
                        createdAt = gJson.optLong("createdAt")
                    ),
                    role = json.optString("role", "member"),
                    mutedUntil = json.optLong("mutedUntil"),
                    joinedAt = json.optLong("joinedAt"),
                    memberCount = json.optInt("memberCount", 0)
                )
            )
        }
        return list
    }

    private fun parseFriendRequests(jsonArr: JSONArray): List<FriendRequest> {
        val list = mutableListOf<FriendRequest>()
        for (i in 0 until jsonArr.length()) {
            val json = jsonArr.getJSONObject(i)
            list.add(
                FriendRequest(
                    id = json.getString("id"),
                    fromId = json.getString("fromId"),
                    toId = json.getString("toId"),
                    message = json.optString("message", ""),
                    status = json.getString("status"),
                    createdAt = json.optLong("createdAt"),
                    fromUser = json.optJSONObject("fromUser")?.let { parseUser(it) },
                    toUser = json.optJSONObject("toUser")?.let { parseUser(it) }
                )
            )
        }
        return list
    }

    private fun parseChatMessage(json: JSONObject): ChatMessage {
        return ChatMessage(
            id = json.getString("id"),
            type = json.getString("type"),
            fromId = json.getString("fromId"),
            fromNickname = json.optString("fromNickname", ""),
            toId = json.optString("toId").ifBlank { null },
            groupId = json.optString("groupId").ifBlank { null },
            contentType = json.optString("contentType", "text"),
            content = json.optString("content", ""),
            image = json.optString("image").ifBlank { null },
            audio = json.optString("audio").ifBlank { null },
            timestamp = json.optLong("timestamp")
        )
    }

    private fun parsePresence(json: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = json.getString(key)
        }
        return map
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}
