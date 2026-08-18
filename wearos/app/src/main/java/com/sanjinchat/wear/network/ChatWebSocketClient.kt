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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

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

    private var currentToken: String? = null

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

    private var manualDisconnect = false

    fun connect(token: String) {
        currentToken = token
        manualDisconnect = false
        reconnectJob?.cancel()
        doConnect(token)
    }

    private fun doConnect(token: String) {
        if (webSocket != null) return

        _connectionState.value = ConnectionState.Connecting

        val wsUrl = buildWsUrl(token)
        val request = Request.Builder().url(wsUrl).build()

        webSocket = client().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                scope.launch {
                    _events.emit(ChatEvent.Error("WebSocket 已连接"))
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                cleanupSocket()
                if (!manualDisconnect) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                cleanupSocket()
                scope.launch {
                    _connectionState.value = ConnectionState.Error(t.message ?: "连接失败")
                    _events.emit(ChatEvent.Error(t.message ?: "连接失败"))
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

    private fun cleanupSocket() {
        webSocket = null
        _connectionState.value = ConnectionState.Disconnected
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
        webSocket?.close(1000, "User disconnect")
        webSocket = null
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
                        val user = parseUser(json.getJSONObject("user"))
                        _currentUser.value = user
                        _connectionState.value = ConnectionState.Connected(user)
                        _events.emit(ChatEvent.Connected(user))
                    }
                    "private_message" -> {
                        val msg = parseChatMessage(json.getJSONObject("message"))
                        _events.emit(ChatEvent.PrivateMessage(msg))
                    }
                    "group_message" -> {
                        val msg = parseChatMessage(json.getJSONObject("message"))
                        _events.emit(ChatEvent.GroupMessage(msg))
                    }
                    "friends:update" -> {
                        val list = parseFriends(json.getJSONArray("friends"))
                        _friends.value = list
                        _events.emit(ChatEvent.FriendsUpdate(list))
                    }
                    "groups:update" -> {
                        val list = parseGroups(json.getJSONArray("groups"))
                        _groups.value = list
                        _events.emit(ChatEvent.GroupsUpdate(list))
                    }
                    "friend_requests:update" -> {
                        val list = parseFriendRequests(json.getJSONArray("requests"))
                        _friendRequests.value = list
                        _events.emit(ChatEvent.FriendRequestsUpdate(list))
                    }
                    "presence" -> {
                        val statuses = parsePresence(json.getJSONObject("statuses"))
                        updateFriendsPresence(statuses)
                        _events.emit(ChatEvent.PresenceUpdate(statuses))
                    }
                    "error" -> {
                        val msg = json.optString("message", "未知错误")
                        _connectionState.value = ConnectionState.Error(msg)
                        _events.emit(ChatEvent.Error(msg))
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
            toId = json.optString("toId"),
            groupId = json.optString("groupId"),
            contentType = json.optString("contentType", "text"),
            content = json.optString("content", ""),
            image = json.optString("image"),
            audio = json.optString("audio"),
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
