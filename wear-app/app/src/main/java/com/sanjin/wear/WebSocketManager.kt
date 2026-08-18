// ==================== WebSocket 实时消息管理器 ====================
package com.sanjin.wear

import com.sanjin.wear.model.ApiResponse
import com.sanjin.wear.model.ChatMessage
import com.sanjin.wear.model.User
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.jsonObject
import okhttp3.*
import org.json.JSONObject

/**
 * WebSocket 连接状态
 */
enum class WsState {
    CONNECTING,   // 连接中
    CONNECTED,    // 已连接（在线）
    DISCONNECTED, // 已断开
    RECONNECTING  // 正在重连
}

/**
 * WebSocket 管理器
 * - 负责连接 SanjinChat /ws 端点
 * - 统一分发消息事件（通过 Flow 发送给 UI）
 * - 自动重连
 * - 屏幕关闭时断开，唤醒时重连（省电）
 */
class WebSocketManager private constructor() {

    companion object {
        /** 单例 */
        val instance: WebSocketManager by lazy { WebSocketManager() }

        /** 重连间隔（毫秒） */
        private const val RECONNECT_INTERVAL = 3000L
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(30, java.util.concurrent.TimeUnit.SECONDS) // 心跳
        .build()

    private var ws: WebSocket? = null
    private var scope: CoroutineScope? = null
    private var reconnectJob: Job? = null

    /** 当前服务器地址 */
    private var server: String = ""
    /** 当前登录 Token */
    private var token: String = ""

    // ======== 状态与事件 ========
    private val _state = MutableStateFlow(WsState.DISCONNECTED)
    val state: StateFlow<WsState> get() = _state

    /** 收到的原始服务端消息 */
    private val _rawMessages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val rawMessages: SharedFlow<JSONObject> get() = _rawMessages

    /** 解析后的私聊/群聊消息 */
    private val _chatMessages = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 64)
    val chatMessages: SharedFlow<ChatMessage> get() = _chatMessages

    /** 新消息到达事件（用于震动通知） */
    private val _notification = MutableSharedFlow<ChatMessage>(extraBufferCapacity = 16)
    val notification: SharedFlow<ChatMessage> get() = _notification

    /** 当前登录用户 */
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> get() = _currentUser

    /** 好友列表更新 */
    private val _friendsUpdate = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val friendsUpdate: SharedFlow<Unit> get() = _friendsUpdate

    /** 群组列表更新 */
    private val _groupsUpdate = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val groupsUpdate: SharedFlow<Unit> get() = _groupsUpdate

    // ================================================================
    // 连接/断开
    // ================================================================

    /**
     * 初始化并连接 WebSocket
     * @param appScope 全局协程作用域
     * @param server 服务器地址（如 your-server.com:8181）
     * @param token 登录令牌
     */
    fun connect(appScope: CoroutineScope, server: String, token: String) {
        if (token.isBlank()) return
        this.scope = appScope
        this.server = server
        this.token = token
        cancelReconnect()
        openConnection()
    }

    /**
     * 打开连接（内部）
     *
     * 服务器地址支持四种前缀（与 HTTP API 互通）：
     *   - ws://host:port      → WebSocket 明文  ws://host:port/ws
     *   - wss://host:port     → WebSocket 加密  wss://host:port/ws
     *   - http://host:port    → 转明文 WS       ws://host:port/ws
     *   - https://host:port   → 转加密 WSS      wss://host:port/ws
     *   - 裸地址 host:port    → 默认加密 wss（与 API 默认 https 对应）
     */
    private fun openConnection() {
        if (token.isBlank()) return
        _state.value = if (ws != null) WsState.RECONNECTING else WsState.CONNECTING

        val base = server.trimEnd('/')
        val (wsScheme, hostPart) = when {
            base.startsWith("ws://")    -> "ws://"  to base.removePrefix("ws://")
            base.startsWith("wss://")   -> "wss://" to base.removePrefix("wss://")
            base.startsWith("http://")  -> "ws://"  to base.removePrefix("http://")
            base.startsWith("https://") -> "wss://" to base.removePrefix("https://")
            else -> "wss://" to base   // 默认加密连接
        }
        val url = "$wsScheme$hostPart/ws?token=${java.net.URLEncoder.encode(token, "UTF-8")}"

        try {
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _state.value = WsState.CONNECTED
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope?.launch(Dispatchers.Default) { dispatch(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    val wasConnected = _state.value == WsState.CONNECTED
                    _state.value = WsState.DISCONNECTED
                    ws = null
                    // 已登录时才自动重连
                    if (wasConnected && token.isNotBlank()) scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val wasConnected = _state.value == WsState.CONNECTED
                    _state.value = WsState.DISCONNECTED
                    ws = null
                    if (wasConnected || token.isNotBlank()) scheduleReconnect()
                }
            })
        } catch (e: Exception) {
            _state.value = WsState.DISCONNECTED
            scheduleReconnect()
        }
    }

    /**
     * 主动断开（例如灭屏时）
     */
    fun disconnect() {
        cancelReconnect()
        ws?.let { w ->
            try { w.close(1000, "client_offline") } catch (_: Exception) {}
            ws = null
        }
        _state.value = WsState.DISCONNECTED
    }

    /**
     * 屏幕唤醒：恢复连接
     */
    fun resume() {
        if (_state.value != WsState.CONNECTED && token.isNotBlank()) {
            openConnection()
        }
    }

    /** 屏幕关闭：断开以省电 */
    fun pause() {
        disconnect()
    }

    /** 完全退出登录时调用 */
    fun logout() {
        cancelReconnect()
        disconnect()
        token = ""
        server = ""
        _currentUser.value = null
    }

    // ================================================================
    // 重连逻辑
    // ================================================================

    private fun scheduleReconnect() {
        cancelReconnect()
        reconnectJob = scope?.launch {
            delay(RECONNECT_INTERVAL)
            if (token.isNotBlank()) openConnection()
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ================================================================
    // 消息分发
    // ================================================================

    private fun dispatch(raw: String) {
        try {
            val obj = JSONObject(raw)
            val type = obj.optString("type", "")
            _rawMessages.tryEmit(obj)

            when (type) {
                // ===== 连接成功：返回当前用户 =====
                "connected" -> {
                    val userJson = obj.optJSONObject("user") ?: return
                    val user = ApiClient.json.decodeFromString<User>(userJson.toString())
                    _currentUser.value = user
                }
                // ===== 私聊消息 =====
                "private_message" -> {
                    val msg = parseMessage(obj, "private_message") ?: return
                    _chatMessages.tryEmit(msg)
                    if (msg.fromId != _currentUser.value?.id) _notification.tryEmit(msg)
                }
                // ===== 群聊消息 =====
                "group_message" -> {
                    val msg = parseMessage(obj, "group_message") ?: return
                    _chatMessages.tryEmit(msg)
                    if (msg.fromId != _currentUser.value?.id) _notification.tryEmit(msg)
                }
                // ===== 列表更新事件：通知上层拉取 =====
                "friends:update", "friend:removed", "friend_request:accepted",
                "friend_request:rejected", "friend_request:sent" -> {
                    _friendsUpdate.tryEmit(Unit)
                }
                "groups:update", "group:joined", "group:left", "group:dismissed",
                "group:member_kicked" -> {
                    _groupsUpdate.tryEmit(Unit)
                    _friendsUpdate.tryEmit(Unit) // 会话列表可能变
                }
                // ===== 用户资料变化：重新拉取好友列表 =====
                "user:nickname_updated", "user:avatar_updated", "user:account_deleted" -> {
                    _friendsUpdate.tryEmit(Unit)
                    _groupsUpdate.tryEmit(Unit)
                }
            }
        } catch (e: Exception) { /* 忽略解析错误 */ }
    }

    /** 把 JSONObject 解析为 ChatMessage */
    private fun parseMessage(obj: JSONObject, type: String): ChatMessage? {
        try {
            val text = obj.toString()
            val tmp = ApiClient.json.parseToJsonElement(text).jsonObject
            return ChatMessage(
                id = tmp["id"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() }
                    ?: System.currentTimeMillis().toString(),
                type = type,
                fromId = tmp["fromId"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() } ?: "",
                fromNickname = tmp["fromNickname"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() },
                toId = tmp["toId"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() },
                groupId = tmp["groupId"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() },
                contentType = tmp["contentType"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() } ?: "text",
                content = tmp["content"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() } ?: "",
                image = tmp["image"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() },
                audio = tmp["audio"]?.let { if (it.toString().startsWith("\"")) it.toString().trim('"') else it.toString() },
                duration = tmp["duration"]?.toString()?.toIntOrNull(),
                timestamp = tmp["timestamp"]?.toString()?.toLongOrNull() ?: System.currentTimeMillis()
            )
        } catch (e: Exception) { return null }
    }

    // ================================================================
    // 发送消息
    // ================================================================

    /** 发送 WebSocket 消息体 */
    fun send(obj: Map<String, Any?>): Boolean {
        val json = JSONObject(obj).toString()
        return try { ws?.send(json) == true } catch (e: Exception) { false }
    }

    /** 发送私聊文字消息 */
    fun sendPrivateMessage(toId: String, content: String): Boolean {
        if (content.isBlank()) return false
        return send(mapOf(
            "type" to "private_message",
            "toId" to toId,
            "contentType" to "text",
            "content" to content
        ))
    }

    /** 发送群聊文字消息 */
    fun sendGroupMessage(groupId: String, content: String): Boolean {
        if (content.isBlank()) return false
        return send(mapOf(
            "type" to "group_message",
            "groupId" to groupId,
            "contentType" to "text",
            "content" to content
        ))
    }

    /** 查询在线状态 */
    fun queryOnline(): Boolean = send(mapOf("type" to "query_online"))

    /** 发送好友请求 */
    fun sendFriendRequest(toId: String, message: String = ""): Boolean = send(mapOf(
        "type" to "friend_request:send",
        "toId" to toId,
        "message" to message
    ))

    /** 接受好友请求 */
    fun acceptFriendRequest(requestId: String): Boolean = send(mapOf(
        "type" to "friend_request:accept",
        "requestId" to requestId
    ))

    /** 拒绝好友请求 */
    fun rejectFriendRequest(requestId: String): Boolean = send(mapOf(
        "type" to "friend_request:reject",
        "requestId" to requestId
    ))

    /** 删除好友 */
    fun removeFriend(friendId: String): Boolean = send(mapOf(
        "type" to "friend:remove",
        "friendId" to friendId
    ))

    /** 创建群 */
    fun createGroup(name: String, password: String? = null, description: String? = null): Boolean {
        val payload = mutableMapOf(
            "type" to "group:create",
            "name" to name
        )
        if (!password.isNullOrBlank()) payload["password"] = password
        if (!description.isNullOrBlank()) payload["description"] = description
        return send(payload)
    }

    /** 加入群 */
    fun joinGroup(groupNumber: String, password: String? = null): Boolean {
        val payload = mutableMapOf(
            "type" to "group:join",
            "groupNumber" to groupNumber
        )
        if (!password.isNullOrBlank()) payload["password"] = password
        return send(payload)
    }

    /** 退出群 */
    fun leaveGroup(groupId: String): Boolean = send(mapOf("type" to "group:leave", "groupId" to groupId))
}
