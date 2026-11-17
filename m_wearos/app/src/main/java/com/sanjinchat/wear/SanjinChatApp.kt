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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** 通知点击后要打开的会话目标 */
data class ChatTarget(val isGroup: Boolean, val id: String, val name: String)

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

    lateinit var callManager: com.sanjinchat.wear.call.CallManager
        private set

    /**
     * 当前正在查看的聊天会话标识：
     * - 私聊：friendId
     * - 群聊：groupId
     * 为 null 表示当前不在任何聊天界面（此时收到消息应弹出系统通知）
     */
    @Volatile
    var activeConversation: String? = null

    /**
     * 自动登录（cookie 恢复）失败时给登录页的一次性提示，例如"登录已过期，请重新登录"。
     * 登录页读取后立即清空，保证只提示一次。
     */
    @Volatile
    var pendingLoginNotice: String? = null

    /** 本次进程是否已做过自动更新检查（Boot 与 Main 共用标记，避免重复弹窗） */
    @Volatile
    var updateCheckedThisSession: Boolean = false

    // 点击通知后待打开的会话：由 MainActivity 写入，MainScreen 进入主界面后消费
    private val _pendingChatTarget = MutableStateFlow<ChatTarget?>(null)
    val pendingChatTarget: StateFlow<ChatTarget?> = _pendingChatTarget.asStateFlow()

    fun requestOpenChat(target: ChatTarget) { _pendingChatTarget.value = target }
    fun consumeChatTarget() { _pendingChatTarget.value = null }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    /** 断网重连后完成云端消息同步的信号：聊天页 / 会话列表据此刷新 */
    private val _messageSyncSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val messageSyncSignal: SharedFlow<Unit> = _messageSyncSignal.asSharedFlow()

    /** 上次成功同步的时刻；只有比它更新的消息才算"断网期间漏收" */
    @Volatile
    private var lastSyncAt: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 崩溃日志写入文件：方便排查闪退（如 WebRTC 视频通话相关 native crash）
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(dir, "crash_${System.currentTimeMillis()}.log")
                file.writeText(
                    "thread=${thread.name}\n" +
                        "time=${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n" +
                        throwable.stackTraceToString()
                )
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        preferenceManager = PreferenceManager(this)
        apiClient = ApiClient(this)
        webSocketClient = ChatWebSocketClient()
        notificationHelper = NotificationHelper(this)
        messageCache = MessageCache(this)
        callManager = com.sanjinchat.wear.call.CallManager(this, webSocketClient)

        // 设备标识：每台设备首次启动生成一次 UUID，上报给服务端，用于多设备登录识别
        runCatching {
            ChatWebSocketClient.deviceId = runBlocking { preferenceManager.getOrPutDeviceId() }
            @Suppress("DEPRECATION")
            ChatWebSocketClient.deviceName = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "未知设备"
        }

        // 默认使用固定服务器地址 + ws 协议，用户可在主界面「高级设置」修改
        val defaultAddress = "chat.sanjinx.com:8181"
        val defaultProtocol = "ws"
        var savedAddress = runBlocking { preferenceManager.serverAddress.first() }
        val savedProtocol = runBlocking { preferenceManager.serverProtocol.first() }
        // 一次性迁移：老版本把旧域名写进了 DataStore，仅改默认值不会生效，这里强制切到新服务器
        if (savedAddress.contains("sanjin666.dpdns.org")) {
            savedAddress = defaultAddress
            runBlocking { preferenceManager.saveServerAddress(defaultAddress) }
        }
        val address = savedAddress.takeUnless { it.isBlank() } ?: defaultAddress
        val protocol = savedProtocol.takeUnless { it.isBlank() } ?: defaultProtocol
        apiClient.serverAddress = address
        apiClient.serverProtocol = protocol
        webSocketClient.serverAddress = address
        webSocketClient.serverProtocol = protocol

        // 监听 WebSocket 消息事件，在收到新消息时发送系统通知
        startMessageNotificationListener()

        // 通话记录：无论用户是否停留在聊天页，都写入本地消息缓存
        appScope.launch {
            callManager.callLogs.collect { log ->
                runCatching {
                    val meId = webSocketClient.currentUser.value?.id ?: ""
                    val msg = com.sanjinchat.wear.model.ChatMessage(
                        id = "call_${log.timestamp}",
                        type = "private_message",
                        fromId = meId,
                        fromNickname = "",
                        toId = log.peerId,
                        contentType = "call",
                        content = log.label,
                        callMedia = log.media,
                        callSeconds = log.durationSec,
                        timestamp = log.timestamp
                    )
                    messageCache.saveMessage(MessageCache.privateConvId(log.peerId), msg)
                }
            }
        }

        // 来电通知的自动清理：接通/挂断/拒接后撤掉通知栏里的来电提醒
        appScope.launch {
            callManager.state.collect { st ->
                if (st is com.sanjinchat.wear.call.CallState.Active ||
                    st is com.sanjinchat.wear.call.CallState.Connecting ||
                    st is com.sanjinchat.wear.call.CallState.Idle
                ) {
                    runCatching { notificationHelper.cancelIncomingCallNotification() }
                }
            }
        }

        // 保活：如果用户之前打开常驻通知，则启动前台服务（跨冷启动自动恢复）
        val keepAlive = runBlocking { preferenceManager.keepAlive.first() }
        if (keepAlive) ensureKeepAliveService(start = true)

        // 杀后台后冷启动：先用本地保存的登录信息填充 currentUser，保证 WS 连上前 ChatScreen 就能正确判断消息归属
        // 注意：token 校验与 WebSocket 连接统一交给 BootScreen 的自动登录流程，这里不再 connect，
        // 避免 Application 与启动页两个 connect 互相关闭对方 socket（之前"卡在连接中"的根因之一）
        appScope.launch {
            val token = preferenceManager.token.first()
            if (!token.isNullOrBlank()) {
                val savedUserId = preferenceManager.userId.first()
                val savedUsername = preferenceManager.username.first()
                val savedNickname = preferenceManager.nickname.first()
                if (!savedUserId.isNullOrBlank()) {
                    // 用本地保存的信息构造最小 User（足够判断 isSelf），不发起网络请求
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
                // 从云端同步最近 10 天消息到本地缓存，并清理本地过期消息
                val myId = savedUserId
                if (!myId.isNullOrBlank()) {
                    // 冷启动不把历史消息计为"未读"，只把之后的断线漏收算作新消息
                    lastSyncAt = System.currentTimeMillis()
                    runCatching { syncMessagesFromServer(token, myId, countMissedAsUnread = false) }
                    // 本地清理：删除 10 天前的消息（含文本/语音/图片）
                    messageCache.cleanupExpired()
                }
            }
        }

        // 断网重连：恢复连接后自动从服务器拉取消息，补齐断网期间对方发来的消息
        appScope.launch {
            var hasEverConnected = false
            webSocketClient.connectionState.collect { state ->
                if (state is ConnectionState.Connected) {
                    // 首次连上由冷启动流程同步；之后每次重新连上都视为"断线恢复"
                    if (hasEverConnected) {
                        val token = webSocketClient.currentToken
                        val myId = state.user.id.ifBlank {
                            webSocketClient.currentUser.value?.id.orEmpty()
                        }
                        if (!token.isNullOrBlank() && myId.isNotBlank()) {
                            runCatching {
                                syncMessagesFromServer(token, myId, countMissedAsUnread = true)
                                _messageSyncSignal.tryEmit(Unit)
                            }
                        }
                    }
                    hasEverConnected = true
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

    /**
     * 从服务器拉取最近 10 天消息，与本地缓存按消息 id 比对去重后写入。
     *
     * @param countMissedAsUnread 是否把 lastSyncAt 之后、别人发来的消息算作"断网期间漏收"：
     *        计入未读红点并弹通知。冷启动传 false，避免把历史消息全部标成未读。
     */
    private suspend fun syncMessagesFromServer(
        token: String,
        myId: String,
        countMissedAsUnread: Boolean
    ) {
        val since = lastSyncAt
        val msgs = apiClient.syncMessages(token).getOrNull() ?: return
        for (msg in msgs) {
            val convId = when {
                msg.type == "private_message" && msg.fromId == myId ->
                    msg.toId?.let { MessageCache.privateConvId(it) }
                msg.type == "private_message" ->
                    MessageCache.privateConvId(msg.fromId)
                msg.type == "group_message" && !msg.groupId.isNullOrBlank() ->
                    MessageCache.groupConvId(msg.groupId!!)
                else -> null
            } ?: continue
            // 按 id 去重，本地已有的不会重复
            messageCache.saveMessage(convId, msg)

            if (!countMissedAsUnread) continue
            if (msg.fromId == myId) continue
            if (msg.timestamp <= since) continue

            // 断线期间别人发来的新消息：计未读 + 弹通知（当前正在看的会话只清未读）
            val peerId = if (msg.type == "private_message") msg.fromId else msg.groupId.orEmpty()
            if (peerId.isBlank()) continue
            if (activeConversation == peerId) {
                webSocketClient.clearUnread(convId)
                continue
            }
            webSocketClient.bumpUnread(convId)
            runCatching {
                val sender = msg.fromNickname.ifBlank { msg.fromId }
                if (msg.type == "group_message") {
                    val groupName = webSocketClient.groups.value
                        .firstOrNull { it.group.id == peerId }?.group?.name
                        ?.takeIf { it.isNotBlank() } ?: "群聊"
                    notificationHelper.showMessageNotification(
                        msg = msg,
                        senderName = groupName,
                        conversationTag = peerId,
                        bodySenderName = sender,
                        extraName = groupName
                    )
                } else {
                    notificationHelper.showMessageNotification(
                        msg = msg,
                        senderName = sender,
                        conversationTag = peerId,
                        extraName = sender
                    )
                }
            }
        }
        lastSyncAt = System.currentTimeMillis()
    }

    private fun startMessageNotificationListener() {
        notificationJob?.cancel()
        // 死循环 + Supervisor：任何异常（存文件/弹通知失败等）都只跳过这一条，继续 collect，确保永远不中断接收
        notificationJob = appScope.launch {
            while (true) {
                try {
                    webSocketClient.events.collect { event ->
                        // 通话信令交给 CallManager 处理
                        runCatching { callManager.handleEvent(event) }
                        // 收到通话邀请：无论应用在前台还是后台，都弹系统通知
                        if (event is ChatEvent.CallInvite) {
                            val st = callManager.state.value
                            if (st is com.sanjinchat.wear.call.CallState.Incoming) {
                                runCatching {
                                    notificationHelper.showIncomingCallNotification(
                                        st.peerId, st.peerName, st.media
                                    )
                                }
                            }
                        }
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
                                        // 即使正在看这个会话，也照样弹通知（只是不再累计未读）
                                        if (activeConversation != msg.fromId) {
                                            webSocketClient.bumpUnread(convId)
                                        } else {
                                            webSocketClient.clearUnread(convId)
                                        }
                                        val sender = msg.fromNickname.ifBlank { msg.fromId }
                                        notificationHelper.showMessageNotification(
                                            msg = msg,
                                            senderName = sender,
                                            conversationTag = msg.fromId,
                                            extraName = sender
                                        )
                                    }
                                    is ChatEvent.GroupMessage -> {
                                        val msg = event.message
                                        val gid = msg.groupId ?: return@runCatching
                                        val convId = MessageCache.groupConvId(gid)
                                        messageCache.saveMessage(convId, msg)
                                        val cu = webSocketClient.currentUser.value?.id
                                        if (msg.fromId == cu) return@runCatching
                                        // 即使正在看这个群，也照样弹通知（只是不再累计未读）
                                        if (activeConversation != gid) {
                                            webSocketClient.bumpUnread(convId)
                                        } else {
                                            webSocketClient.clearUnread(convId)
                                        }
                                        val sender = msg.fromNickname.ifBlank { msg.fromId }
                                        // 群通知：标题显示群名，正文显示「发送者：内容」
                                        val groupName = webSocketClient.groups.value
                                            .firstOrNull { it.group.id == gid }?.group?.name
                                            ?.takeIf { it.isNotBlank() } ?: "群聊"
                                        notificationHelper.showMessageNotification(
                                            msg = msg,
                                            senderName = groupName,
                                            conversationTag = gid,
                                            bodySenderName = sender,
                                            extraName = groupName
                                        )
                                    }
                                    is ChatEvent.DeviceLogin -> {
                                        notificationHelper.showDeviceLoginNotification(
                                            event.deviceName, event.ip
                                        )
                                    }
                                    is ChatEvent.DeviceLogout -> {
                                        // 本设备被其他设备踢下线：弹通知 + 断开 + 跳登录页
                                        notificationHelper.showDeviceKickedNotification(event.reason)
                                        appScope.launch {
                                            runCatching { webSocketClient.disconnect() }
                                            // 跳登录页（直接切状态）
                                            runCatching {
                                                val navIntent = android.content.Intent(
                                                    this@SanjinChatApp,
                                                    com.sanjinchat.wear.MainActivity::class.java
                                                ).apply {
                                                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                    putExtra("force_logout", event.reason)
                                                }
                                                startActivity(navIntent)
                                            }
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
