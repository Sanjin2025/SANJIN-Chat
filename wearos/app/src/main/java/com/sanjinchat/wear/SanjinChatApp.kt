package com.sanjinchat.wear

import android.app.Application
import com.sanjinchat.wear.data.MessageCache
import com.sanjinchat.wear.data.PreferenceManager
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.network.ChatWebSocketClient
import com.sanjinchat.wear.network.ConnectionState
import com.sanjinchat.wear.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SanjinChatApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set

    lateinit var apiClient: ApiClient
        private set

    lateinit var webSocketClient: ChatWebSocketClient
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    lateinit var messageCache: MessageCache
        private set

    /**
     * 当前正在查看的聊天会话标识：
     * - 私聊：friendId
     * - 群聊：groupId
     * 为 null 表示当前不在任何聊天界面（此时收到消息应弹出系统通知）
     */
    @Volatile
    var activeConversation: String? = null

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
        apiClient = ApiClient(this)
        webSocketClient = ChatWebSocketClient()
        notificationHelper = NotificationHelper(this)
        messageCache = MessageCache(this)

        // 默认使用固定服务器地址 + ws 协议，用户可在主界面「高级设置」修改
        val defaultAddress = "sanjin666.dpdns.org:8181"
        val defaultProtocol = "ws"
        val savedAddress = runBlocking { preferenceManager.serverAddress.first() }
        val savedProtocol = runBlocking { preferenceManager.serverProtocol.first() }
        val address = savedAddress.takeUnless { it.isBlank() } ?: defaultAddress
        val protocol = savedProtocol.takeUnless { it.isBlank() } ?: defaultProtocol
        apiClient.serverAddress = address
        apiClient.serverProtocol = protocol
        webSocketClient.serverAddress = address
        webSocketClient.serverProtocol = protocol

        // 监听 WebSocket 消息事件，在收到新消息时发送系统通知
        startMessageNotificationListener()

        // 保活：如果用户之前打开常驻通知，则启动前台服务（跨冷启动自动恢复）
        val keepAlive = runBlocking { preferenceManager.keepAlive.first() }
        if (keepAlive) ensureKeepAliveService(start = true)

        // 杀后台后冷启动恢复：有 token 时自动重连 WS + 恢复 currentUser
        appScope.launch {
            val token = preferenceManager.token.first()
            if (!token.isNullOrBlank()) {
                // 1. 先从 HTTP 恢复 currentUser（WS 连上前 ChatScreen 就可用）
                val savedUserId = preferenceManager.userId.first()
                val savedUsername = preferenceManager.username.first()
                val savedNickname = preferenceManager.nickname.first()
                val profileUser = runCatching { apiClient.getMyProfile(token).getOrNull() }.getOrNull()
                if (profileUser != null) {
                    webSocketClient.setCurrentUser(profileUser)
                } else if (!savedUserId.isNullOrBlank()) {
                    // HTTP 失败：用 PreferenceManager 保存的信息构造一个最小 User（足够判断 isSelf）
                    webSocketClient.setCurrentUser(com.sanjinchat.wear.model.User(
                        id = savedUserId,
                        username = savedUsername ?: "",
                        nickname = savedNickname ?: savedUsername ?: "",
                        avatar = null,
                        avatarType = null,
                        avatarEmoji = null,
                        role = "user",
                        createdAt = 0L
                    ))
                }
                // 2. 自动连接 WebSocket
                webSocketClient.connect(token)
                // 3. 从云端同步最近 10 天消息到本地缓存，并清理本地过期消息
                val myId = profileUser?.id ?: savedUserId
                if (!myId.isNullOrBlank()) {
                    runCatching { apiClient.syncMessages(token).getOrNull() }
                        .getOrNull()
                        ?.let { msgs ->
                            for (msg in msgs) {
                                val convId = when {
                                    msg.type == "private_message" && msg.fromId == myId ->
                                        msg.toId?.let { MessageCache.privateConvId(it) }
                                    msg.type == "private_message" ->
                                        MessageCache.privateConvId(msg.fromId)
                                    msg.type == "group_message" && !msg.groupId.isNullOrBlank() ->
                                        MessageCache.groupConvId(msg.groupId!!)
                                    else -> null
                                }
                                if (convId != null) {
                                    messageCache.saveMessage(convId, msg)
                                }
                            }
                        }
                    // 本地清理：删除 10 天前的消息（含文本/语音/图片）
                    messageCache.cleanupExpired()
                }
            }
        }

        // 看门狗：连接状态一旦变成 Error / Disconnected → 自动触发 scheduleReconnect
        // 即使 WS 重连调度因异常中断，也能由这里兜底
        appScope.launch {
            webSocketClient.connectionState.collect { state ->
                when (state) {
                    is ConnectionState.Error -> {
                        val t = webSocketClient.currentToken
                        if (!t.isNullOrBlank()) {
                            // 给 WS 内部的 scheduleReconnect 兜底：如果 2s 内仍没进入 Connecting，再手动触发
                            kotlinx.coroutines.delay(2000)
                            if (webSocketClient.connectionState.value !is ConnectionState.Connecting &&
                                webSocketClient.connectionState.value !is ConnectionState.Connected) {
                                runCatching { webSocketClient.connect(t) }
                            }
                        }
                    }
                    is ConnectionState.Disconnected -> {
                        val t = webSocketClient.currentToken
                        if (!t.isNullOrBlank() && !webSocketClient.isManualDisconnect()) {
                            kotlinx.coroutines.delay(1500)
                            if (webSocketClient.connectionState.value is ConnectionState.Disconnected) {
                                runCatching { webSocketClient.connect(t) }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /** 统一入口：开启或关闭常驻保活前台服务。 */
    fun ensureKeepAliveService(start: Boolean) {
        val ctx = this
        val intent = android.content.Intent(ctx, KeepAliveService::class.java)
        try {
            if (start) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } else {
                ctx.stopService(intent)
            }
        } catch (_: Throwable) { /* ignore */ }
    }

    private fun startMessageNotificationListener() {
        notificationJob?.cancel()
        // 死循环 + Supervisor：任何异常（存文件/弹通知失败等）都只跳过这一条，继续 collect，确保永远不中断接收
        notificationJob = appScope.launch {
            while (true) {
                try {
                    webSocketClient.events.collect { event ->
                        // 每条事件独立子协程 + runCatching 包裹，单条失败绝不打断后续 collect
                        launch {
                            runCatching {
                                when (event) {
                                    is ChatEvent.PrivateMessage -> {
                                        val msg = event.message
                                        val cu = webSocketClient.currentUser.value?.id
                                        val otherId = if (msg.fromId == cu) msg.toId ?: "" else msg.fromId
                                        val convId = MessageCache.privateConvId(otherId)
                                        messageCache.saveMessage(convId, msg)
                                        if (msg.fromId == cu) return@runCatching
                                        if (activeConversation != msg.fromId) {
                                            val sender = msg.fromNickname.ifBlank { msg.fromId }
                                            notificationHelper.showMessageNotification(
                                                msg = msg,
                                                senderName = sender,
                                                conversationTag = msg.fromId
                                            )
                                        }
                                    }
                                    is ChatEvent.GroupMessage -> {
                                        val msg = event.message
                                        val gid = msg.groupId ?: return@runCatching
                                        val convId = MessageCache.groupConvId(gid)
                                        messageCache.saveMessage(convId, msg)
                                        val cu = webSocketClient.currentUser.value?.id
                                        if (msg.fromId == cu) return@runCatching
                                        if (activeConversation != gid) {
                                            val sender = msg.fromNickname.ifBlank { msg.fromId }
                                            notificationHelper.showMessageNotification(
                                                msg = msg,
                                                senderName = sender,
                                                conversationTag = gid
                                            )
                                        }
                                    }
                                    else -> {}
                                }
                            }.onFailure { err ->
                                android.util.Log.w("SanjinChatApp", "通知/缓存处理异常：${err.message}")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("SanjinChatApp", "events.collect 中断，1s 后重启：${t.message}")
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    /** 清除消息缓存（退出登录时调用） */
    fun clearMessageCache() {
        messageCache.clearAll()
    }

    companion object {
        lateinit var instance: SanjinChatApp
            private set
    }
}
