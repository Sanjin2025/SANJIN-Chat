package com.sanjinchat.wear.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.data.MessageCache
import com.sanjinchat.wear.model.ChatMessage
import com.sanjinchat.wear.model.Friend
import com.sanjinchat.wear.model.FriendRequest
import com.sanjinchat.wear.model.GroupMembership
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.network.ConnectionState
import com.sanjinchat.wear.network.Diagnostics
import com.sanjinchat.wear.network.ProbeResult
import com.sanjinchat.wear.ui.theme.ErrorDeep
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.ExpressiveMotion
import com.sanjinchat.wear.ui.theme.GrayOffline
import com.sanjinchat.wear.ui.theme.GreenOnline
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import com.sanjinchat.wear.ui.theme.PurpleDeep
import com.sanjinchat.wear.ui.theme.PurpleLight
import com.sanjinchat.wear.ui.theme.SurfaceVariant
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MainTab { CHATS, FRIENDS, GROUPS, DISCOVER, SETTINGS }

private enum class DialogKind {
    None,
    // 好友
    AddFriend,
    FriendRequests,
    DeleteFriendConfirm,
    // 群聊
    CreateGroup,
    JoinGroup,
    LeaveGroupConfirm,
    // 配对码
    PairCode,
    // 连接诊断
    Diagnostics,
    // 高级设置（服务器地址 + ws/wss）
    AdvancedSettings,
    // 用户/群搜索
    UserSearch,
    // 关于
    About,
    // 个人资料（改昵称/Emoji头像/图片头像/注销账号）
    Profile,
    // 群成员管理（成员列表+踢人+禁言+设管理员+解散群）
    GroupMembers,
    // 公告板（查看+管理员增删改）
    Announcements,
    // 加群密码输入
    JoinGroupPassword,
    // 扫码登录其他设备
    QrScanner,
    // 应用内 DPI / 显示密度切换
    DpiSettings,
    // 曝光板（30min 限时公开资料）
    ExposureBoard,
    // 保活（常驻通知 + 关闭电池优化）
    KeepAlive,
}

@Composable
fun MainScreen(navController: NavController) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()

    val currentUser by app.webSocketClient.currentUser.collectAsState()
    val connectionState by app.webSocketClient.connectionState.collectAsState()
    val friends by app.webSocketClient.friends.collectAsState()
    val groups by app.webSocketClient.groups.collectAsState()
    val friendRequests by app.webSocketClient.friendRequests.collectAsState()

    var currentTab by remember { mutableStateOf(MainTab.CHATS) }
    val listState = rememberLazyListState()

    // 圆屏检测：优先用户手动设置，否则自动检测系统
    val screenShape by app.preferenceManager.screenShape.collectAsState(initial = "auto")
    val isRound = when (screenShape) {
        "round" -> true
        "square" -> false
        else -> {
            val config = androidx.compose.ui.platform.LocalConfiguration.current
            (config.screenLayout and android.content.res.Configuration.SCREENLAYOUT_ROUND_MASK) ==
                android.content.res.Configuration.SCREENLAYOUT_ROUND_YES
        }
    }
    // 圆屏下 Tab 和内容需要更大水平 padding，防止图标被边缘裁剪
    val tabHPadding = if (isRound) 14.dp else 6.dp
    val contentHPadding = if (isRound) 12.dp else 4.dp

    // 聊天列表最近消息预览（从本地缓存加载）
    var lastMessages by remember { mutableStateOf<Map<String, ChatMessage>>(emptyMap()) }

    // 进入页面：强制使用固定服务器地址 → 连 WS → 拉取好友/群/请求列表
    LaunchedEffect(Unit) {
        val token = app.preferenceManager.token.first()
        // 强制固定服务器地址和协议，不从 PreferenceManager 读取
        val serverAddr = "sanjin666.dpdns.org:8181"
        val protocol = "ws"
        app.apiClient.serverAddress = serverAddr
        app.webSocketClient.serverAddress = serverAddr
        app.apiClient.serverProtocol = protocol
        app.webSocketClient.serverProtocol = protocol
        if (!token.isNullOrBlank()) {
            // 先拉 HTTP：好友 / 好友请求 / 群
            listOf(
                launch {
                    app.apiClient.getFriends(token)
                        .onSuccess { list -> app.webSocketClient.setFriendsSnapshot(list) }
                },
                launch {
                    app.apiClient.getFriendRequests(token)
                        .onSuccess { list -> app.webSocketClient.setFriendRequestsSnapshot(list) }
                },
                launch {
                    app.apiClient.getGroups(token)
                        .onSuccess { list -> app.webSocketClient.setGroupsSnapshot(list) }
                }
            ).joinAll()
            // 只要还没 Connected（Error/Disconnected/Connecting 过久），都兜底触发一次 connect
            // connect() 内部会强制关闭半死旧 socket 并建立新连接，保证不会卡在 Connecting
            if (connectionState !is ConnectionState.Connected) {
                runCatching { app.webSocketClient.connect(token) }
            }
        }
    }

    // 加载聊天列表的最近消息预览（好友/群列表变化时重新加载）
    LaunchedEffect(friends, groups) {
        val convIds = friends.map { MessageCache.privateConvId(it.userId) } +
                groups.map { MessageCache.groupConvId(it.group.id) }
        if (convIds.isNotEmpty()) {
            lastMessages = app.messageCache.getLastMessages(convIds)
        }
    }

    // -------- Dialog 状态 --------
    var dialogKind by remember { mutableStateOf(DialogKind.None) }
    // 添加好友
    var addTarget by remember { mutableStateOf("") }
    var addMessage by remember { mutableStateOf("") }
    // 通讯录快速查找
    var friendsSearchQuery by remember { mutableStateOf("") }
    // 选定项
    var pendingFriend by remember { mutableStateOf<Friend?>(null) }
    var pendingGroup by remember { mutableStateOf<GroupMembership?>(null) }
    // 创建群
    var newGroupName by remember { mutableStateOf("") }
    var newGroupDesc by remember { mutableStateOf("") }
    var newGroupPassword by remember { mutableStateOf("") }
    // 加入群
    var joinGroupId by remember { mutableStateOf("") }
    var joinGroupPassword by remember { mutableStateOf("") }
    // 配对码
    var pairCodeInfo by remember { mutableStateOf<ApiClient.PairCodeInfo?>(null) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var pairCountdown by remember { mutableStateOf(0) }
    // 连接诊断
    var diagResults by remember { mutableStateOf<List<ProbeResult>?>(null) }
    var diagRunning by remember { mutableStateOf(false) }
    // 高级设置（服务器/协议）
    var advAddress by remember { mutableStateOf("") }
    var advProtocol by remember { mutableStateOf("ws") }
    var advError by remember { mutableStateOf<String?>(null) }
    // 用户/群查找
    var searchKind by remember { mutableStateOf("user") } // "user" or "group"
    var searchKeyword by remember { mutableStateOf("") }
    var searchRunning by remember { mutableStateOf(false) }
    var searchUserResults by remember { mutableStateOf<List<ApiClient.SearchUserResult>>(emptyList()) }
    var searchGroupResults by remember { mutableStateOf<List<ApiClient.SearchGroupResult>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var searchAddMsg by remember { mutableStateOf("") }
    var searchJoinPwd by remember { mutableStateOf("") }
    // 加群目标（有密码时弹密码输入）
    var pendingJoinGroup by remember { mutableStateOf<ApiClient.SearchGroupResult?>(null) }
    var joinInProgress by remember { mutableStateOf(false) }
    var joinResultMsg by remember { mutableStateOf<String?>(null) }
    // 扫码登录其他设备
    var qrScanResult by remember { mutableStateOf<String?>(null) }
    var qrScanError by remember { mutableStateOf<String?>(null) }
    var qrConfirmInProgress by remember { mutableStateOf(false) }
    // 群成员管理面板目标群
    var groupManageTarget by remember { mutableStateOf<GroupMembership?>(null) }

    // 收到新消息时刷新预览 / WS 错误反馈
    LaunchedEffect(Unit) {
        app.webSocketClient.events.collect { event ->
            when (event) {
                is ChatEvent.PrivateMessage, is ChatEvent.GroupMessage -> {
                    val convIds = friends.map { MessageCache.privateConvId(it.userId) } +
                            groups.map { MessageCache.groupConvId(it.group.id) }
                    if (convIds.isNotEmpty()) {
                        lastMessages = app.messageCache.getLastMessages(convIds)
                    }
                }
                is ChatEvent.Error -> {
                    // 显示 WS 错误（加群密码错误等）
                    if (joinInProgress) {
                        joinResultMsg = event.message
                        joinInProgress = false
                    } else {
                        searchError = event.message
                    }
                }
                else -> {}
            }
        }
    }

    // 配对码倒计时：info 变化时重置 ticker，每秒递减（防异常）
    LaunchedEffect(pairCodeInfo) {
        val info = pairCodeInfo
        if (info == null) {
            pairCountdown = 0
        } else {
            try {
                while (true) {
                    val remaining = (info.expiresAt - System.currentTimeMillis()) / 1000L
                    pairCountdown = remaining.toInt().coerceAtLeast(0)
                    if (remaining <= 0L) break
                    delay(1000L)
                }
            } catch (_: Exception) {
                pairCountdown = 0
            }
        }
    }

    val connText = when (connectionState) {
        is ConnectionState.Connected -> "已连接"
        ConnectionState.Connecting -> "连接中..."
        is ConnectionState.Error -> "错误"
        ConnectionState.Disconnected -> "已断开"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (dialogKind != DialogKind.None) {
            // ===== 全屏页面（替代 Dialog） =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (dialogKind) {
                    DialogKind.AddFriend -> AddFriendPanel(
                        target = addTarget,
                        message = addMessage,
                        onTargetChange = { addTarget = it },
                        onMessageChange = { addMessage = it },
                        onCancel = { dialogKind = DialogKind.None },
                        onConfirm = {
                            scope.launch {
                                app.webSocketClient.sendFriendRequest(addTarget.trim(), addMessage.trim())
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.FriendRequests -> FriendRequestsPanel(
                        requests = friendRequests,
                        currentUserId = currentUser?.id,
                        onAccept = { req ->
                            scope.launch { app.webSocketClient.acceptFriendRequest(req.id) }
                        },
                        onReject = { req ->
                            scope.launch { app.webSocketClient.rejectFriendRequest(req.id) }
                        },
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.DeleteFriendConfirm -> ConfirmPanel(
                        title = "删除好友",
                        desc = "确定删除 ${pendingFriend?.user?.nickname?.ifBlank { pendingFriend?.user?.username } ?: "此好友"}？",
                        confirmLabel = "删除",
                        destructive = true,
                        onCancel = { dialogKind = DialogKind.None },
                        onConfirm = {
                            scope.launch {
                                pendingFriend?.let { app.webSocketClient.removeFriend(it.userId) }
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.CreateGroup -> CreateGroupPanel(
                        name = newGroupName,
                        password = newGroupPassword,
                        desc = newGroupDesc,
                        onNameChange = { newGroupName = it },
                        onPasswordChange = { newGroupPassword = it },
                        onDescChange = { newGroupDesc = it },
                        onCancel = { dialogKind = DialogKind.None },
                        onConfirm = {
                            scope.launch {
                                app.webSocketClient.createGroup(
                                    newGroupName.trim(),
                                    newGroupDesc.trim(),
                                    if (newGroupPassword.isBlank()) null else newGroupPassword.trim()
                                )
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.JoinGroup -> JoinGroupPanel(
                        number = joinGroupId,
                        password = joinGroupPassword,
                        onNumberChange = { joinGroupId = it },
                        onPasswordChange = { joinGroupPassword = it },
                        onCancel = { dialogKind = DialogKind.None },
                        onConfirm = {
                            scope.launch {
                                app.webSocketClient.joinGroup(joinGroupId.trim(), joinGroupPassword.trim())
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.LeaveGroupConfirm -> ConfirmPanel(
                        title = "退出群聊",
                        desc = "确定退出「${pendingGroup?.group?.name ?: ""}」？",
                        confirmLabel = "退出",
                        destructive = true,
                        onCancel = { dialogKind = DialogKind.None },
                        onConfirm = {
                            scope.launch {
                                pendingGroup?.let { app.webSocketClient.leaveGroup(it.group.id) }
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.PairCode -> PairCodePanel(
                        pairCodeInfo = pairCodeInfo,
                        error = pairError,
                        countdown = pairCountdown,
                        onGenerate = {
                            scope.launch {
                                try {
                                    pairError = null
                                    val token = app.preferenceManager.token.first()
                                    if (token.isNullOrBlank()) {
                                        pairError = "未登录"
                                        return@launch
                                    }
                                    val genResult = app.apiClient.generatePairCode(token)
                                    genResult.fold(
                                        onSuccess = { pairCodeInfo = it },
                                        onFailure = { e -> pairError = e.message ?: "生成配对码失败" }
                                    )
                                } catch (e: Exception) {
                                    pairError = "生成配对码失败：${e.message ?: "未知错误"}"
                                }
                            }
                        },
                        onCancel = {
                            scope.launch {
                                pairError = null
                                val token = app.preferenceManager.token.first()
                                if (!token.isNullOrBlank()) {
                                    app.apiClient.cancelPairCode(token)
                                }
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.Diagnostics -> DiagnosticsPanel(
                        running = diagRunning,
                        results = diagResults,
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.AdvancedSettings -> AdvancedSettingsPanel(
                        address = advAddress,
                        onAddressChange = { advAddress = it },
                        protocol = advProtocol,
                        onProtocolChange = { advProtocol = it },
                        error = advError,
                        onCancel = { dialogKind = DialogKind.None },
                        onSave = {
                            scope.launch {
                                val addr = advAddress.trim().removeSuffix("/")
                                if (addr.isBlank()) {
                                    advError = "地址不能为空"
                                    return@launch
                                }
                                app.preferenceManager.saveServerAddress(addr)
                                app.preferenceManager.saveServerProtocol(advProtocol)
                                app.apiClient.serverAddress = addr
                                app.webSocketClient.serverAddress = addr
                                app.apiClient.serverProtocol = advProtocol
                                app.webSocketClient.serverProtocol = advProtocol
                                advError = null
                                dialogKind = DialogKind.None
                            }
                        }
                    )
                    DialogKind.UserSearch -> UserSearchPanel(
                        kind = searchKind,
                        onKindChange = { searchKind = it },
                        keyword = searchKeyword,
                        onKeywordChange = { searchKeyword = it },
                        running = searchRunning,
                        userResults = searchUserResults,
                        groupResults = searchGroupResults,
                        error = searchError,
                        addMessage = searchAddMsg,
                        onAddMessageChange = { searchAddMsg = it },
                        onSearch = {
                            scope.launch {
                                searchRunning = true; searchError = null
                                val token = app.preferenceManager.token.first()
                                if (token.isNullOrBlank()) { searchError = "未登录"; searchRunning = false; return@launch }
                                if (searchKeyword.isBlank()) { searchError = "请输入关键词"; searchRunning = false; return@launch }
                                if (searchKind == "user") {
                                    app.apiClient.searchUsers(token, searchKeyword.trim()).fold(
                                        onSuccess = { searchUserResults = it; searchGroupResults = emptyList() },
                                        onFailure = { e -> searchError = e.message ?: "搜索失败" }
                                    )
                                } else {
                                    app.apiClient.searchGroups(token, searchKeyword.trim()).fold(
                                        onSuccess = { searchGroupResults = it; searchUserResults = emptyList() },
                                        onFailure = { e -> searchError = e.message ?: "搜索失败" }
                                    )
                                }
                                searchRunning = false
                            }
                        },
                        onAddFriend = { u ->
                            scope.launch {
                                val token = app.preferenceManager.token.first()
                                if (!token.isNullOrBlank()) {
                                    app.webSocketClient.sendFriendRequest(u.id, searchAddMsg.trim())
                                    searchAddMsg = ""
                                }
                            }
                        },
                        onJoinGroup = { g ->
                            scope.launch {
                                if (g.hasPassword && !g.joined) {
                                    pendingJoinGroup = g
                                    dialogKind = DialogKind.JoinGroupPassword
                                } else {
                                    app.webSocketClient.joinGroup(g.groupNumber, "")
                                    dialogKind = DialogKind.None
                                }
                            }
                        },
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.About -> AboutPanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.Profile -> ProfilePanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.GroupMembers -> groupManageTarget?.let { pg ->
                        GroupMembersPanel(
                            group = pg,
                            onClose = { dialogKind = DialogKind.None }
                        )
                    }
                    DialogKind.Announcements -> AnnouncementsPanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.JoinGroupPassword -> pendingJoinGroup?.let { g ->
                        JoinGroupPasswordPanel(
                            group = g,
                            password = joinGroupPassword,
                            onPasswordChange = { joinGroupPassword = it },
                            inProgress = joinInProgress,
                            resultMsg = joinResultMsg,
                            onCancel = { dialogKind = DialogKind.UserSearch },
                            onConfirm = {
                                scope.launch {
                                    joinInProgress = true
                                    joinResultMsg = null
                                    val ok = app.webSocketClient.joinGroup(g.groupNumber, joinGroupPassword.trim())
                                    if (ok) {
                                        joinGroupPassword = ""
                                        pendingJoinGroup = null
                                        joinInProgress = false
                                        dialogKind = DialogKind.None
                                    } else {
                                        joinInProgress = false
                                        joinResultMsg = "发送加群请求失败"
                                    }
                                }
                            }
                        )
                    }
                    DialogKind.QrScanner -> QrScannerPanel(
                        scanResult = qrScanResult,
                        scanError = qrScanError,
                        inProgress = qrConfirmInProgress,
                        onScanSuccess = { result ->
                            qrScanResult = result
                            qrScanError = null
                            scope.launch {
                                qrConfirmInProgress = true
                                val token = app.preferenceManager.token.first()
                                if (!token.isNullOrBlank()) {
                                    app.apiClient.confirmQrCode(token, result).fold(
                                        onSuccess = {
                                            qrScanResult = null
                                            qrScanError = null
                                            qrConfirmInProgress = false
                                            dialogKind = DialogKind.None
                                        },
                                        onFailure = { e ->
                                            qrScanError = e.message ?: "确认失败"
                                            qrConfirmInProgress = false
                                        }
                                    )
                                } else {
                                    qrScanError = "未登录"
                                    qrConfirmInProgress = false
                                }
                            }
                        },
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.DpiSettings -> DpiSettingsPanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.ExposureBoard -> ExposureBoardPanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.KeepAlive -> KeepAlivePanel(
                        onClose = { dialogKind = DialogKind.None }
                    )
                    DialogKind.None -> {}
                }
            }
        } else {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = tabHPadding, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                TabButton(
                    label = "消息",
                    sym = "💬",
                    selected = currentTab == MainTab.CHATS,
                    onClick = { currentTab = MainTab.CHATS },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    label = "好友",
                    sym = "👤",
                    badgeCount = if (currentTab == MainTab.FRIENDS)
                        friendRequests.count { it.status == "pending" && it.toId == currentUser?.id } else 0,
                    selected = currentTab == MainTab.FRIENDS,
                    onClick = { currentTab = MainTab.FRIENDS },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    label = "群聊",
                    sym = "👥",
                    selected = currentTab == MainTab.GROUPS,
                    onClick = { currentTab = MainTab.GROUPS },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    label = "发现",
                    sym = "🔎",
                    selected = currentTab == MainTab.DISCOVER,
                    onClick = { currentTab = MainTab.DISCOVER },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    label = "设置",
                    sym = "⚙",
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    modifier = Modifier.weight(1f)
                )
            }

            // 连接状态提示（Expressive 形变滑入滑出 + 内容尺寸动画）
            val showConnBanner = when (connectionState) {
                is ConnectionState.Connected -> false
                else -> true
            }
            AnimatedVisibility(
                visible = showConnBanner,
                enter = ExpressiveMotion.EnterSlideUp,
                exit = ExpressiveMotion.ExitSlideDown
            ) {
                val banner = when (connectionState) {
                    ConnectionState.Connecting -> Triple(
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        "连接中..."
                    )
                    is ConnectionState.Error -> Triple(ErrorDeep, ErrorLight, "连接错误，请检查网络")
                    ConnectionState.Disconnected -> Triple(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        "已断开，正在重连..."
                    )
                    else -> Triple(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer,
                        ""
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                        .padding(horizontal = contentHPadding, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(banner.first)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(banner.third, fontSize = 8.sp, color = banner.second, fontWeight = FontWeight.Medium)
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = contentHPadding,
                    end = contentHPadding,
                    top = 6.dp,
                    bottom = if (isRound) 24.dp else 40.dp
                ),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                when (currentTab) {
                    MainTab.CHATS -> {
                        item { SectionHeader("聊天") }
                        if (friends.isEmpty() && groups.isEmpty()) {
                            item {
                                EmptyState(text = "暂无聊天\n添加好友或加入群聊以开始")
                            }
                        } else {
                            items(friends) { friend ->
                                val convId = MessageCache.privateConvId(friend.userId)
                                val lastMsg = lastMessages[convId]
                                ChatListItem(
                                    name = friend.user.nickname.ifBlank { friend.user.username },
                                    subtitle = lastMsg?.let { formatMsgPreview(it) }
                                        ?: if (friend.online) "在线" else "离线",
                                    isOnline = friend.online,
                                    avatarEmoji = friend.user.avatarEmoji,
                                    avatarUrl = friend.user.avatar,
                                    onClick = {
                                        val displayName = friend.user.nickname.ifBlank { friend.user.username }
                                        navController.navigate("chat/private/${friend.userId}/${displayName}")
                                    }
                                )
                            }
                            items(groups) { group ->
                                val convId = MessageCache.groupConvId(group.group.id)
                                val lastMsg = lastMessages[convId]
                                ChatListItem(
                                    name = group.group.name,
                                    subtitle = lastMsg?.let { formatMsgPreview(it) }
                                        ?: "${group.memberCount}人 · #${group.group.groupNumber}",
                                    isGroup = true,
                                    onClick = {
                                        navController.navigate("chat/group/${group.group.id}/${group.group.name}")
                                    }
                                )
                            }
                        }
                    }

                    MainTab.FRIENDS -> {
                        item { SectionHeader("好友 (${friends.size})") }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                ActionChip(
                                    label = "添加好友",
                                    sym = "＋",
                                    onClick = {
                                        addTarget = ""
                                        addMessage = ""
                                        dialogKind = DialogKind.AddFriend
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                ActionChip(
                                    label = "查找用户/群",
                                    sym = "🔍",
                                    onClick = {
                                        searchKind = "user"
                                        searchKeyword = ""
                                        searchUserResults = emptyList()
                                        searchGroupResults = emptyList()
                                        searchError = null
                                        searchAddMsg = ""
                                        searchJoinPwd = ""
                                        dialogKind = DialogKind.UserSearch
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                val pendingCount = friendRequests.count { it.status == "pending" && it.toId == currentUser?.id }
                                ActionChip(
                                    label = if (pendingCount > 0) "请求($pendingCount)" else "请求",
                                    sym = "📬",
                                    onClick = { dialogKind = DialogKind.FriendRequests },
                                    modifier = Modifier.weight(1f),
                                    highlighted = pendingCount > 0
                                )
                            }
                        }
                        if (friends.isEmpty()) {
                            item {
                                EmptyState(text = "暂无好友\n点击「添加好友」开始")
                            }
                        } else {
                            // 快速查找
                            item {
                                OutlinedTextField(
                                    value = friendsSearchQuery,
                                    onValueChange = { friendsSearchQuery = it },
                                    label = { Text("查找好友", fontSize = 9.sp) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                                )
                            }
                            val filteredFriends = if (friendsSearchQuery.isBlank()) friends
                                else friends.filter {
                                    it.user.nickname.contains(friendsSearchQuery, true) ||
                                    it.user.username.contains(friendsSearchQuery, true)
                                }
                            if (filteredFriends.isEmpty()) {
                                item { EmptyState(text = "未找到匹配的好友") }
                            } else {
                                items(filteredFriends) { friend ->
                                    FriendListItem(
                                        friend = friend,
                                        onClick = {
                                            val displayName = friend.user.nickname.ifBlank { friend.user.username }
                                            navController.navigate("chat/private/${friend.userId}/${displayName}")
                                        },
                                        onDelete = {
                                            pendingFriend = friend
                                            dialogKind = DialogKind.DeleteFriendConfirm
                                        }
                                    )
                                }
                            }
                        }
                    }

                    MainTab.GROUPS -> {
                        item { SectionHeader("群聊 (${groups.size})") }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                ActionChip(
                                    label = "创建群聊",
                                    sym = "🆕",
                                    onClick = {
                                        newGroupName = ""
                                        newGroupDesc = ""
                                        newGroupPassword = ""
                                        dialogKind = DialogKind.CreateGroup
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                ActionChip(
                                    label = "加入群聊",
                                    sym = "🔑",
                                    onClick = {
                                        joinGroupId = ""
                                        joinGroupPassword = ""
                                        dialogKind = DialogKind.JoinGroup
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (groups.isEmpty()) {
                            item {
                                EmptyState(text = "暂无群聊\n创建或加入一个群吧")
                            }
                        } else {
                            items(groups) { group ->
                                GroupListItem(
                                    group = group,
                                    onClick = {
                                        navController.navigate("chat/group/${group.group.id}/${group.group.name}")
                                    },
                                    onLeave = {
                                        pendingGroup = group
                                        dialogKind = DialogKind.LeaveGroupConfirm
                                    },
                                    onManage = {
                                        groupManageTarget = group
                                        dialogKind = DialogKind.GroupMembers
                                    }
                                )
                            }
                        }
                    }

                    MainTab.DISCOVER -> {
                        item { SectionHeader("发现") }
                        item {
                            AssistChip(
                                onClick = { dialogKind = DialogKind.Announcements },
                                label = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("📢 公告板", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        Text("查看所有公告", fontSize = 8.sp, color = OnSurfaceVariant)
                                    }
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { dialogKind = DialogKind.ExposureBoard },
                                label = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("👀 曝光板", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                        Text("30min 限时公开资料", fontSize = 8.sp, color = OnSurfaceVariant)
                                    }
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    MainTab.SETTINGS -> {
                        item { SectionHeader("设置") }
                        item {
                            // 个人资料入口：显示当前用户头像 + 昵称，点击进入个人资料设置
                            val cu = currentUser
                            val displayName = cu?.nickname?.ifBlank { cu?.username } ?: "未登录"
                            val avatarEmoji = cu?.avatarEmoji
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { dialogKind = DialogKind.Profile }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (!avatarEmoji.isNullOrBlank()) {
                                    Text(avatarEmoji, fontSize = 20.sp)
                                } else {
                                    UserAvatar(name = displayName, size = 28.dp)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "个人资料",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        displayName,
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        item {
                            val serverAddr = app.webSocketClient.serverAddress
                            SettingsCard(
                                title = "服务器地址",
                                content = serverAddr.ifBlank { "未设置" }
                            )
                        }
                        item {
                            val protocol = app.webSocketClient.serverProtocol
                            val addr = app.webSocketClient.serverAddress
                            SettingsCard(
                                title = "服务器协议",
                                content = "${protocol}://${addr.ifBlank { "" }}"
                            )
                        }
                        item {
                            val dpiScale = app.preferenceManager.dpiScale
                                .collectAsState(initial = 1.0f).value
                            val dpiLabel = when {
                                dpiScale <= 0.80f -> "特小 ($dpiScale)"
                                dpiScale <= 0.90f -> "紧凑 ($dpiScale)"
                                dpiScale <= 0.98f -> "偏小 ($dpiScale)"
                                dpiScale in 0.98f..1.02f -> "标准 ($dpiScale)"
                                dpiScale <= 1.15f -> "偏大 ($dpiScale)"
                                dpiScale <= 1.28f -> "大 ($dpiScale)"
                                else -> "超大 ($dpiScale)"
                            }
                            AssistChip(
                                onClick = { dialogKind = DialogKind.DpiSettings },
                                label = {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text("显示密度 / DPI", color = OnSurfaceVariant, fontSize = 8.sp)
                                        Text(dpiLabel, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                                    }
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            AssistChip(
                                onClick = { dialogKind = DialogKind.KeepAlive },
                                label = {
                                    Text("保活设置（通知常驻 / 电池优化）", color = MaterialTheme.colorScheme.secondary, fontSize = 11.sp)
                                },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 生成 6 位配对码（2 分钟有效），让其他设备扫码/输入后免密登录此账号
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val token = app.preferenceManager.token.first()
                                            if (token.isNullOrBlank()) {
                                                pairError = "未登录"
                                                dialogKind = DialogKind.PairCode
                                                return@launch
                                            }
                                            pairError = null
                                            pairCodeInfo = null
                                            dialogKind = DialogKind.PairCode
                                            app.apiClient.getPairStatus(token).fold(
                                                onSuccess = { cached ->
                                                    if (cached != null) {
                                                        pairCodeInfo = cached
                                                    } else {
                                                        app.apiClient.generatePairCode(token).fold(
                                                            onSuccess = { pairCodeInfo = it },
                                                            onFailure = { e -> pairError = e.message ?: "生成配对码失败" }
                                                        )
                                                    }
                                                },
                                                onFailure = { cacheErr ->
                                                    runCatching {
                                                        app.apiClient.generatePairCode(token).fold(
                                                            onSuccess = { pairCodeInfo = it },
                                                            onFailure = { e ->
                                                                pairError = cacheErr.message?.let { m -> "$m；${e.message}" }
                                                                    ?: (e.message ?: "生成配对码失败")
                                                            }
                                                        )
                                                    }.onFailure { e2 ->
                                                        pairError = cacheErr.message ?: e2.message ?: "生成配对码失败"
                                                    }
                                                }
                                            )
                                        } catch (e: Exception) {
                                            // 任何未捕获异常：不会闪退
                                            pairError = "生成配对码失败：${e.message ?: "未知错误"}"
                                            if (dialogKind != DialogKind.PairCode) dialogKind = DialogKind.PairCode
                                        }
                                    }
                                },
                                label = { Text("配对码登录", color = MaterialTheme.colorScheme.primary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 扫码登录其他设备：用摄像头扫描二维码确认登录
                            AssistChip(
                                onClick = {
                                    qrScanResult = null
                                    qrScanError = null
                                    qrConfirmInProgress = false
                                    dialogKind = DialogKind.QrScanner
                                },
                                label = { Text("扫码登录其他设备", color = MaterialTheme.colorScheme.primary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 高级设置：服务器地址 + ws/wss（默认 sanjin666.dpdns.org:8181 + ws）
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        advAddress = app.preferenceManager.serverAddress.first()
                                            .takeUnless { it.isBlank() } ?: "sanjin666.dpdns.org:8181"
                                        advProtocol = app.preferenceManager.serverProtocol.first()
                                            .takeUnless { it.isBlank() } ?: "ws"
                                        advError = null
                                        dialogKind = DialogKind.AdvancedSettings
                                    }
                                },
                                label = { Text("高级设置", color = MaterialTheme.colorScheme.secondary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 连接诊断：DNS / TCP / HTTP / HTTPS / WS / WSS 6 项
                            AssistChip(
                                onClick = {
                                    dialogKind = DialogKind.Diagnostics
                                    diagRunning = true
                                    diagResults = null
                                    scope.launch {
                                        val addr = app.apiClient.serverAddress.ifBlank { "sanjin666.dpdns.org:8181" }
                                        val proto = app.apiClient.serverProtocol
                                        val results = Diagnostics.runAll(addr, proto)
                                        diagResults = results
                                        diagRunning = false
                                    }
                                },
                                label = { Text("连接诊断", color = MaterialTheme.colorScheme.tertiary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        app.webSocketClient.disconnect()
                                        app.clearMessageCache()
                                        app.preferenceManager.clearLoginInfo()
                                        navController.navigate("login") {
                                            popUpTo("login") { inclusive = true }
                                        }
                                    }
                                },
                                label = { Text("退出登录", color = ErrorLight) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = ErrorDeep
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            // 关于：显示版本号与开源地址
                            AssistChip(
                                onClick = { dialogKind = DialogKind.About },
                                label = { Text("关于", color = MaterialTheme.colorScheme.primary) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } // end SETTINGS branch
                }         // end LazyColumn { }
            }             // end Column { Tabs + Content }
        }                 // end Box else branch
    }                         // end outer Box { }
    }
}                             // end MainScreen() fun

// ==================== Panel Composables ====================

@Composable
private fun UserSearchPanel(
    kind: String,
    onKindChange: (String) -> Unit,
    keyword: String,
    onKeywordChange: (String) -> Unit,
    running: Boolean,
    userResults: List<ApiClient.SearchUserResult>,
    groupResults: List<ApiClient.SearchGroupResult>,
    error: String?,
    addMessage: String,
    onAddMessageChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAddFriend: (ApiClient.SearchUserResult) -> Unit,
    onJoinGroup: (ApiClient.SearchGroupResult) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = if (kind == "user") "查找用户" else "查找群聊", onBack = onClose)
        Spacer(Modifier.height(4.dp))
        // 用户/群切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = kind == "user",
                onClick = { onKindChange("user") },
                label = { Text("👤 用户", fontSize = 11.sp) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = kind == "group",
                onClick = { onKindChange("group") },
                label = { Text("👥 群聊", fontSize = 11.sp) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                label = {
                    Text(
                        if (kind == "user") "用户名/昵称/ID" else "群号/群名",
                        fontSize = 10.sp
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onSearch,
                enabled = !running && keyword.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.height(44.dp)
            ) {
                if (running) CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 1.5.dp
                ) else Text("搜索", fontSize = 11.sp)
            }
        }
        if (kind == "user") {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = addMessage,
                onValueChange = onAddMessageChange,
                label = { Text("加好友验证消息（可选）", fontSize = 10.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(3.dp))
            Text(it, color = ErrorLight, fontSize = 10.sp)
        }
        Spacer(Modifier.height(5.dp))
        // 结果列表
        if (kind == "user" && userResults.isNotEmpty()) {
            Text("共 ${userResults.size} 个结果", color = OnSurfaceVariant, fontSize = 9.sp)
            Spacer(Modifier.height(2.dp))
        } else if (kind == "group" && groupResults.isNotEmpty()) {
            Text("共 ${groupResults.size} 个结果", color = OnSurfaceVariant, fontSize = 9.sp)
            Spacer(Modifier.height(2.dp))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceVariant)
        ) {
            if (kind == "user" && userResults.isEmpty() && !running && keyword.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("输入关键词开始搜索", color = OnSurfaceVariant, fontSize = 10.sp)
                }
            } else if (kind == "group" && groupResults.isEmpty() && !running && keyword.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("输入群号或群名开始搜索", color = OnSurfaceVariant, fontSize = 10.sp)
                }
            } else if (!running && kind == "user" && userResults.isEmpty() && keyword.isNotBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到匹配的用户", color = OnSurfaceVariant, fontSize = 10.sp)
                }
            } else if (!running && kind == "group" && groupResults.isEmpty() && keyword.isNotBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到匹配的群聊", color = OnSurfaceVariant, fontSize = 10.sp)
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (kind == "user") {
                        items(userResults) { u ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = u.nickname.ifBlank { u.username },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = buildString {
                                            append("@${u.username}")
                                            if (u.alreadyFriend) append(" · 已为好友")
                                            else if (u.pendingRequest) append(" · 已发送申请")
                                        },
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp
                                    )
                                }
                                Button(
                                    onClick = { onAddFriend(u) },
                                    enabled = !u.alreadyFriend && !u.pendingRequest,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = SurfaceVariant,
                                        disabledContentColor = OnSurfaceVariant
                                    )
                                ) {
                                    Text(
                                        when {
                                            u.alreadyFriend -> "已好友"
                                            u.pendingRequest -> "已申请"
                                            else -> "加好友"
                                        },
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    } else {
                        items(groupResults) { g ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = g.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (g.description.isNotBlank()) {
                                        Text(
                                            text = g.description,
                                            color = OnSurfaceVariant,
                                            fontSize = 9.sp,
                                            maxLines = 2
                                        )
                                    }
                                    Text(
                                        text = buildString {
                                            append("#${g.groupNumber} · ${g.memberCount}人")
                                            if (g.hasPassword) append(" · 🔒")
                                            if (g.isOwner) append(" · 群主")
                                            else if (g.joined) append(" · 已加入")
                                        },
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp
                                    )
                                }
                                Button(
                                    onClick = { onJoinGroup(g) },
                                    enabled = !g.joined,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    modifier = Modifier.height(26.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                        disabledContainerColor = SurfaceVariant,
                                        disabledContentColor = OnSurfaceVariant
                                    )
                                ) {
                                    Text(if (g.joined) "已加入" else "加入", fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("关闭", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun AddFriendPanel(
    target: String,
    message: String,
    onTargetChange: (String) -> Unit,
    onMessageChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "添加好友", onBack = onCancel)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = target,
            onValueChange = onTargetChange,
            label = { Text("用户名/用户ID", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(
            value = message,
            onValueChange = onMessageChange,
            label = { Text("验证消息（可选）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        DialogFooter(onCancel = onCancel, confirmLabel = "发送请求", onConfirm = onConfirm,
            confirmEnabled = target.isNotBlank())
    }
}

@Composable
private fun FriendRequestsPanel(
    requests: List<FriendRequest>,
    currentUserId: String?,
    onAccept: (FriendRequest) -> Unit,
    onReject: (FriendRequest) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "好友请求", onBack = onClose)
        Spacer(Modifier.height(4.dp))
        if (requests.isEmpty()) {
            Text("暂无好友请求", color = OnSurfaceVariant, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭", color = OnSurfaceVariant) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.height((160).dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(requests) { req ->
                    val isIncoming = req.toId == currentUserId
                    val targetUser = if (isIncoming) req.fromUser else req.toUser
                    val name = targetUser
                        ?.let { it.nickname.ifBlank { it.username } } ?: "用户"
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(6.dp)
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(
                                    name = name,
                                    size = 24.dp,
                                    avatarEmoji = targetUser?.avatarEmoji,
                                    avatarUrl = targetUser?.avatar
                                )
                                Spacer(Modifier.width(5.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "${if (isIncoming) "对方发来请求" else "我发出的请求"} · ${req.status}",
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            if (req.message.isNotBlank()) {
                                Text(
                                    text = req.message,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                            if (isIncoming && req.status == "pending") {
                                Spacer(Modifier.height(3.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SmallButton(
                                        label = "接受",
                                        onClick = { onAccept(req) },
                                        primary = true
                                    )
                                    SmallButton(
                                        label = "拒绝",
                                        onClick = { onReject(req) },
                                        primary = false,
                                        destructive = true
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("关闭", color = OnSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun CreateGroupPanel(
    name: String,
    desc: String,
    password: String,
    onNameChange: (String) -> Unit,
    onDescChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "创建群聊", onBack = onCancel)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("群名称", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = desc,
            onValueChange = onDescChange,
            label = { Text("描述（可选）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码（可选）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        DialogFooter(
            onCancel = onCancel,
            confirmLabel = "创建",
            onConfirm = onConfirm,
            confirmEnabled = name.isNotBlank()
        )
    }
}

@Composable
private fun JoinGroupPanel(
    number: String,
    password: String,
    onNumberChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "加入群聊", onBack = onCancel)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = number,
            onValueChange = onNumberChange,
            label = { Text("群号", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码（无则留空）", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        DialogFooter(
            onCancel = onCancel,
            confirmLabel = "加入",
            onConfirm = onConfirm,
            confirmEnabled = number.isNotBlank()
        )
    }
}

@Composable
private fun PairCodePanel(
    pairCodeInfo: ApiClient.PairCodeInfo?,
    error: String?,
    countdown: Int,
    onGenerate: () -> Unit,
    onCancel: () -> Unit
) {
    val code = pairCodeInfo?.pairCode
    val minutes = countdown / 60
    val seconds = countdown % 60
    val expired = code != null && countdown <= 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "配对码登录", onBack = onCancel)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "在另一台设备选择「配对码登录」并输入以下 6 位数字",
            color = OnSurfaceVariant,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))

        if (error != null) {
            Text("错误：$error", color = ErrorLight, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
        }

        if (code != null && code.length >= 6) {
            // 6 位数字大字展示，每 3 位一组
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PairCodeGroup(code.substring(0, 3), expired)
                Spacer(Modifier.width(8.dp))
                PairCodeGroup(code.substring(3, 6), expired)
            }
            Spacer(Modifier.height(6.dp))
            val statusText = when {
                expired -> "已过期，请重新生成"
                else -> "有效期：$minutes:${seconds.toString().padStart(2, '0')}"
            }
            Text(
                text = statusText,
                color = if (expired) ErrorLight else MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onGenerate,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (expired) "重新生成" else "重新生成", fontSize = 11.sp)
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ErrorDeep,
                        contentColor = ErrorLight
                    )
                ) {
                    Text("取消配对", fontSize = 11.sp)
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (error == null && code == null) "生成中..." else "生成失败",
                    color = OnSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onGenerate,
                    enabled = error != null,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("重试", fontSize = 11.sp)
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = OnSurfaceVariant
                    )
                ) {
                    Text("关闭", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun PairCodeGroup(digits: String, expired: Boolean) {
    val bgColor = if (expired) GrayOffline else MaterialTheme.colorScheme.primary
    val fgColor = if (expired) Color.White else MaterialTheme.colorScheme.onPrimary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        digits.forEach { ch ->
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch.toString(),
                    color = fgColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun AdvancedSettingsPanel(
    address: String,
    onAddressChange: (String) -> Unit,
    protocol: String,
    onProtocolChange: (String) -> Unit,
    error: String?,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "高级设置", onBack = onCancel)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text("服务器 host:port", fontSize = 10.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = "默认：sanjin666.dpdns.org:8181",
            color = OnSurfaceVariant,
            fontSize = 9.sp
        )
        Spacer(Modifier.height(6.dp))
        // ws / wss 切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilterChip(
                selected = protocol == "ws",
                onClick = { onProtocolChange("ws") },
                label = { Text("ws://", fontSize = 11.sp) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = protocol == "wss",
                onClick = { onProtocolChange("wss") },
                label = { Text("wss://", fontSize = 11.sp) },
                modifier = Modifier.weight(1f)
            )
        }
        if (!error.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(error, color = ErrorLight, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = OnSurfaceVariant
                )
            ) { Text("取消", fontSize = 11.sp) }
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text("保存并重连", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    running: Boolean,
    results: List<ProbeResult>?,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "连接诊断", onBack = onClose)
        Spacer(Modifier.height(8.dp))
        if (running && results == null) {
            Text("正在探测 DNS/TCP/HTTP/HTTPS/WS/WSS ...", color = OnSurfaceVariant, fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp
            )
        } else {
            val list = results.orEmpty()
            if (list.isEmpty()) {
                Text("无结果", color = OnSurfaceVariant, fontSize = 10.sp)
            } else {
                list.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Pass / fail indicator
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (r.ok) GreenOnline else ErrorLight)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = r.label,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (r.durationMs > 0L) {
                                    Text(
                                        text = "${r.durationMs}ms",
                                        color = OnSurfaceVariant,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                            Text(
                                text = r.detail,
                                color = if (r.ok) OnSurfaceVariant else ErrorLight,
                                fontSize = 9.sp,
                                softWrap = true,
                                maxLines = 4
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) { Text("关闭", fontSize = 11.sp) }
    }
}

@Composable
private fun ConfirmPanel(
    title: String,
    desc: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Column {
        DialogTitle(title)
        Spacer(Modifier.height(6.dp))
        Text(
            text = desc,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        DialogFooter(
            onCancel = onCancel,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            destructive = destructive
        )
    }
}

// ==================== Small Composables ====================

@Composable
private fun DialogTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
}

/**
 * MD3 风格页面头部：返回按钮 + 标题。用于全屏页面顶部，方便手表返回。
 */
@Composable
private fun PageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            modifier = Modifier
        ) {
            Text(
                text = "←",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = title,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DialogFooter(
    onCancel: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text("取消", color = OnSurfaceVariant, fontSize = 11.sp)
        }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) ErrorDeep else MaterialTheme.colorScheme.primary,
                contentColor = if (destructive) ErrorLight else MaterialTheme.colorScheme.onPrimary
            ),
            modifier = Modifier.weight(1f)
        ) {
            Text(confirmLabel, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SmallButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    destructive: Boolean = false
) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
        colors = when {
            destructive -> ButtonDefaults.buttonColors(
                containerColor = ErrorDeep, contentColor = ErrorLight
            )
            primary -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
            else -> ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        }
    ) {
        Text(label, fontSize = 10.sp)
    }
}

@Composable
private fun ActionChip(
    label: String,
    sym: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sym, fontSize = 11.sp)
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 10.sp)
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .height(32.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = if (highlighted) PurpleDeep else MaterialTheme.colorScheme.surface,
            labelColor = if (highlighted) PurpleLight else MaterialTheme.colorScheme.onSurface
        )
    )
}

@Composable
private fun TabButton(
    label: String,
    sym: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeCount: Int = 0
) {
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onClick() }
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else Color.Transparent
                )
                .padding(vertical = 3.dp)
        ) {
            Text(sym, fontSize = 13.sp)
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else OnSurfaceVariant,
                fontSize = 9.sp
            )
        }
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 1.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(ErrorDeep),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (badgeCount > 9) "•" else badgeCount.toString(),
                    color = ErrorLight,
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
    )
}

/** 格式化消息预览文本（用于聊天列表 subtitle） */
private fun formatMsgPreview(msg: ChatMessage): String {
    val sender = msg.fromNickname.ifBlank { msg.fromId }
    val body = when (msg.contentType) {
        "image" -> "[图片]"
        "audio" -> "[语音]"
        else -> msg.content
    }
    return "$sender: $body"
}

@Composable
private fun ChatListItem(
    name: String,
    subtitle: String,
    isOnline: Boolean = false,
    isGroup: Boolean = false,
    avatarEmoji: String? = null,
    avatarUrl: String? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh),
        label = "chatItemScale"
    )
    Card(
        onClick = onClick,
        interactionSource = interaction,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(6.dp)
        ) {
            UserAvatar(
                name = name,
                size = 26.dp,
                isGroup = isGroup,
                avatarEmoji = avatarEmoji,
                avatarUrl = avatarUrl
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = OnSurfaceVariant,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!isGroup) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) GreenOnline else GrayOffline)
                )
            }
        }
    }
}

@Composable
private fun FriendListItem(
    friend: Friend,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(7.dp)
        ) {
            UserAvatar(
                name = friend.user.nickname.ifBlank { friend.user.username },
                size = 28.dp,
                avatarEmoji = friend.user.avatarEmoji,
                avatarUrl = friend.user.avatar
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = friend.user.nickname.ifBlank { friend.user.username },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (friend.online) GreenOnline else GrayOffline
                            )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (friend.online) "在线" else "离线",
                        color = OnSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ErrorDeep)
                    .clickable { onDelete() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("删", color = ErrorLight, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun GroupListItem(
    group: GroupMembership,
    onClick: () -> Unit,
    onLeave: () -> Unit,
    onManage: () -> Unit = {}
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(7.dp)
        ) {
            UserAvatar(
                name = group.group.name,
                size = 28.dp,
                isGroup = true
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.group.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = "#${group.group.groupNumber} · ${group.memberCount}人",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.width(2.dp))
            // 管理按钮：打开群成员管理面板
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onManage() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("管", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GrayOffline)
                    .clickable { onLeave() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Text("退", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun UserAvatar(
    name: String,
    size: androidx.compose.ui.unit.Dp,
    isGroup: Boolean = false,
    avatarEmoji: String? = null,
    avatarUrl: String? = null
) {
    val initial = name.firstOrNull()?.uppercase() ?: '?'
    val colors = listOf(
        Color(0xFF6750A4), Color(0xFF386A20), Color(0xFF8C1D18),
        Color(0xFF006874), Color(0xFF7D5260), Color(0xFF4F378B)
    )
    val bgColor = colors[(name.hashCode() and 0x7fffffff) % colors.size]

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (isGroup) PurpleDeep else bgColor),
        contentAlignment = Alignment.Center
    ) {
        when {
            // 优先显示 emoji 头像
            !avatarEmoji.isNullOrBlank() -> {
                Text(
                    text = avatarEmoji,
                    fontSize = (size.value * 0.56).sp,
                    textAlign = TextAlign.Center
                )
            }
            // 其次显示图片头像（Coil 异步加载）
            !avatarUrl.isNullOrBlank() -> {
                coil.compose.AsyncImage(
                    model = avatarUrl,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
            // 兜底：首字母
            else -> {
                Text(
                    text = initial.toString(),
                    fontSize = (size.value * 0.48).sp,
                    color = if (isGroup) PurpleLight else Color.White,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    ) {
        Text(
            text = text,
            color = OnSurfaceVariant,
            textAlign = TextAlign.Center,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: String) {
    Card(
        onClick = { /* reserved */ },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 4.dp)
        ) {
            Text(
                text = title,
                color = OnSurfaceVariant,
                fontSize = 8.sp
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = content,
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AboutPanel(onClose: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "关于", onBack = onClose)
        Spacer(Modifier.height(8.dp))

        Text(
            text = "三金聊天",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        val versionName = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName
        }.getOrNull() ?: "1.0.0"
        Text(
            text = "版本 $versionName",
            fontSize = 11.sp,
            color = OnSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        // 制作者信息
        Text(
            text = "制作者",
            fontSize = 11.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "SANJIN",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))

        // 邮箱（点击可调用邮件应用）
        Text(
            text = "联系邮箱",
            fontSize = 11.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        val email = "guohao_1999@outlook.com"
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    runCatching { context.startActivity(intent) }
                }
                .padding(8.dp)
        ) {
            Text(
                text = email,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = "开源地址",
            fontSize = 11.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(2.dp))
        // 直接点击行即可打开浏览器跳转 GitHub
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Sanjin2025/SANJIN-Chat"))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    runCatching { context.startActivity(intent) }
                }
                .padding(8.dp)
        ) {
            Text(
                text = "https://github.com/Sanjin2025/SANJIN-Chat",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(Modifier.height(12.dp))
        DialogFooter(
            onCancel = onClose,
            confirmLabel = "关闭",
            onConfirm = onClose
        )
    }
}

// ==================== 个人资料面板 ====================

@Composable
private fun ProfilePanel(onClose: () -> Unit) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentUser by app.webSocketClient.currentUser.collectAsState()

    var nickname by remember { mutableStateOf("") }
    var emojiPicked by remember { mutableStateOf<String?>(null) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    // 修改密码
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }

    // 进入时初始化昵称
    LaunchedEffect(currentUser?.id) {
        nickname = currentUser?.nickname ?: ""
        emojiPicked = currentUser?.avatarEmoji
    }

    // 12 种 Emoji 白名单
    val avatarEmojis = listOf("🐱","🐶","🐰","🦊","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵")

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            processing = true; error = null; success = null
            val token = app.preferenceManager.token.first()
            if (token.isNullOrBlank()) { processing = false; error = "未登录"; return@launch }
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "avatar-${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        FileOutputStream(target).use { out -> inp.copyTo(out) }
                    }
                    target
                }.getOrNull()
            }
            if (file == null) { processing = false; error = "读取图片失败"; return@launch }
            // 服务端仅返回 { ok, url }，不返回 user；本地刷新通过 currentUser 流（WS 推送 users:update）
            app.apiClient.uploadAvatarImage(token, file)
                .onSuccess { url -> success = "头像已更新"; emojiPicked = null }
                .onFailure { e -> error = "上传头像：${e.message}" }
            processing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "个人资料", onBack = onClose)
        Spacer(Modifier.height(6.dp))
        currentUser?.let { u ->
            Text(text = "账号：${u.username}", fontSize = 11.sp, color = OnSurfaceVariant)
            Text(text = "ID：${u.id}", fontSize = 9.sp, color = OnSurfaceVariant)
            if (u.role == "admin") {
                Text(text = "★ 管理员", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        } ?: Text("未获取到用户信息", color = OnSurfaceVariant, fontSize = 11.sp)

        Spacer(Modifier.height(10.dp))

        // 改昵称
        Text("修改昵称（1-20 字符）", fontSize = 11.sp, color = OnSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { if (it.length <= 20) nickname = it },
            label = { Text("昵称", fontSize = 10.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val n = nickname.trim()
                if (n.length !in 1..20) { error = "昵称长度 1-20"; success = null; return@Button }
                scope.launch {
                    processing = true; error = null; success = null
                    val token = app.preferenceManager.token.first()
                    if (token.isNullOrBlank()) { processing = false; error = "未登录"; return@launch }
                    app.apiClient.updateNickname(token, n)
                        .onSuccess { success = "昵称已更新" }
                        .onFailure { e -> error = "修改昵称：${e.message}" }
                    processing = false
                }
            },
            enabled = !processing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存昵称", fontSize = 10.sp) }

        Spacer(Modifier.height(10.dp))

        // 设置 Emoji 头像
        Text("设置 Emoji 头像", fontSize = 11.sp, color = OnSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            modifier = Modifier.height(120.dp)
        ) {
            items(avatarEmojis) { e ->
                val selected = emojiPicked == e
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable {
                            scope.launch {
                                processing = true; error = null; success = null
                                val token = app.preferenceManager.token.first()
                                if (token.isNullOrBlank()) { processing = false; error = "未登录"; return@launch }
                                // 服务端仅返回 { ok, avatar: emoji }，不返回 user
                                app.apiClient.setAvatarEmoji(token, e)
                                    .onSuccess { avatar -> emojiPicked = avatar; success = "头像已更新" }
                                    .onFailure { e2 -> error = "设置头像：${e2.message}" }
                                processing = false
                            }
                        },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 16.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                pickAvatar.launch(Intent.createChooser(i, "选择头像图片"))
            },
            enabled = !processing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("上传图片头像（≤2MB）", fontSize = 10.sp) }

        Spacer(Modifier.height(10.dp))

        // 修改密码（需验证旧密码，新密码 6-32 位）
        Text("修改密码（6-32 位）", fontSize = 11.sp, color = OnSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        OutlinedTextField(
            value = oldPwd,
            onValueChange = { oldPwd = it },
            label = { Text("旧密码", fontSize = 10.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = newPwd,
            onValueChange = { if (it.length <= 32) newPwd = it },
            label = { Text("新密码", fontSize = 10.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = confirmPwd,
            onValueChange = { if (it.length <= 32) confirmPwd = it },
            label = { Text("确认新密码", fontSize = 10.sp) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                val o = oldPwd.trim()
                val n = newPwd.trim()
                val c = confirmPwd.trim()
                if (n.length !in 6..32) { error = "新密码 6-32 位"; success = null; return@Button }
                if (n != c) { error = "两次密码不一致"; success = null; return@Button }
                scope.launch {
                    processing = true; error = null; success = null
                    val token = app.preferenceManager.token.first()
                    if (token.isNullOrBlank()) { processing = false; error = "未登录"; return@launch }
                    app.apiClient.changePassword(token, o, n)
                        .onSuccess {
                            success = "密码已更新，请重新登录"
                            oldPwd = ""; newPwd = ""; confirmPwd = ""
                            // 修改密码后强制重新登录（旧 token 仍有效，但出于安全考虑提示）
                        }
                        .onFailure { e -> error = "修改密码：${e.message}" }
                    processing = false
                }
            },
            enabled = !processing,
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存密码", fontSize = 10.sp) }

        error?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = ErrorLight, fontSize = 10.sp)
        }
        success?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))

        // 注销账号（危险操作，二次确认）
        Button(
            onClick = { showDeleteDialog = true; deletePassword = "" },
            enabled = !processing,
            colors = ButtonDefaults.buttonColors(
                containerColor = ErrorDeep,
                contentColor = Color.White
            ),
            modifier = Modifier.fillMaxWidth()
        ) { Text("永久注销账号", fontSize = 10.sp) }

        Spacer(Modifier.height(8.dp))
        DialogFooter(
            onCancel = onClose,
            confirmLabel = "关闭",
            onConfirm = onClose
        )
    }

    // 注销确认对话框
    if (showDeleteDialog) {
        Dialog(onDismissRequest = { showDeleteDialog = false }) {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("永久注销账号", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ErrorLight)
                    Spacer(Modifier.height(4.dp))
                    Text("此操作不可恢复，将清空你的好友/群/消息等所有数据。请输入密码确认：",
                        fontSize = 10.sp, color = OnSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("密码", fontSize = 10.sp) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { showDeleteDialog = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("取消", fontSize = 10.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    processing = true; error = null
                                    val token = app.preferenceManager.token.first()
                                    if (token.isNullOrBlank()) { processing = false; error = "未登录"; return@launch }
                                    app.apiClient.deleteAccount(token, deletePassword)
                                        .onSuccess {
                                            // 注销成功：清理本地状态并跳转登录页
                                            app.webSocketClient.disconnect()
                                            app.clearMessageCache()
                                            app.preferenceManager.clearLoginInfo()
                                            showDeleteDialog = false
                                            onClose()
                                        }
                                        .onFailure { e -> error = "注销失败：${e.message}" }
                                    processing = false
                                }
                            },
                            enabled = deletePassword.isNotBlank() && !processing,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ErrorDeep,
                                contentColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        ) { Text("确认注销", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

// ==================== 群成员管理面板 ====================

@Composable
private fun GroupMembersPanel(
    group: GroupMembership,
    onClose: () -> Unit
) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val currentUser by app.webSocketClient.currentUser.collectAsState()
    val context = LocalContext.current

    var members by remember { mutableStateOf<List<ApiClient.GroupMember>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var selectedMember by remember { mutableStateOf<ApiClient.GroupMember?>(null) }

    val myRole = group.role
    val isOwner = currentUser?.id == group.group.ownerId
    val canManage = isOwner || myRole == "admin"

    // 加载成员列表
    LaunchedEffect(group.group.id) {
        loading = true; error = null
        val token = app.preferenceManager.token.first()
        if (token.isNullOrBlank()) { loading = false; error = "未登录"; return@LaunchedEffect }
        app.apiClient.getGroupMembers(token, group.group.id)
            .onSuccess { members = it }
            .onFailure { e -> error = "加载成员：${e.message}" }
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "群成员管理", onBack = onClose)
        Spacer(Modifier.height(4.dp))
        Text(text = group.group.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(text = "#${group.group.groupNumber} · ${members.size}人 · 你是${roleLabel(myRole)}",
            fontSize = 9.sp, color = OnSurfaceVariant)
        Spacer(Modifier.height(6.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        } else if (error != null) {
            Text(error ?: "", color = ErrorLight, fontSize = 10.sp)
        } else {
            LazyColumn(
                modifier = Modifier.height(200.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(members) { m ->
                    GroupMemberRow(
                        member = m,
                        isSelf = currentUser?.id == m.userId,
                        isOwnerView = isOwner,
                        canManage = canManage,
                        onClick = { selectedMember = m },
                        onKick = {
                            scope.launch {
                                tip = null
                                val ok = app.webSocketClient.kickGroupMember(group.group.id, m.userId)
                                tip = if (ok) "已踢出 ${m.user.nickname.ifBlank { m.user.username }}" else "WS 未连接"
                                if (ok) {
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.apiClient.getGroupMembers(token, group.group.id)
                                            .onSuccess { members = it }
                                    }
                                }
                            }
                        },
                        onMute = {
                            scope.launch {
                                tip = null
                                val until = System.currentTimeMillis() + 10 * 60 * 1000L  // 默认禁言 10 分钟
                                val ok = app.webSocketClient.muteGroupMember(group.group.id, m.userId, until)
                                tip = if (ok) "已禁言 10 分钟" else "WS 未连接"
                                if (ok) {
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.apiClient.getGroupMembers(token, group.group.id)
                                            .onSuccess { members = it }
                                    }
                                }
                            }
                        },
                        onUnmute = {
                            scope.launch {
                                tip = null
                                val ok = app.webSocketClient.unmuteGroupMember(group.group.id, m.userId)
                                tip = if (ok) "已解除禁言" else "WS 未连接"
                                if (ok) {
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.apiClient.getGroupMembers(token, group.group.id)
                                            .onSuccess { members = it }
                                    }
                                }
                            }
                        },
                        onSetAdmin = {
                            scope.launch {
                                tip = null
                                val ok = app.webSocketClient.setGroupAdmin(group.group.id, m.userId)
                                tip = if (ok) "已设为管理员" else "WS 未连接"
                                if (ok) {
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.apiClient.getGroupMembers(token, group.group.id)
                                            .onSuccess { members = it }
                                    }
                                }
                            }
                        },
                        onRemoveAdmin = {
                            scope.launch {
                                tip = null
                                val ok = app.webSocketClient.removeGroupAdmin(group.group.id, m.userId)
                                tip = if (ok) "已撤销管理员" else "WS 未连接"
                                if (ok) {
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.apiClient.getGroupMembers(token, group.group.id)
                                            .onSuccess { members = it }
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        tip?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
        }

        Spacer(Modifier.height(8.dp))
        // 仅群主可解散
        if (isOwner) {
            Button(
                onClick = {
                    scope.launch {
                        tip = null
                        val ok = app.webSocketClient.dismissGroup(group.group.id)
                        if (ok) {
                            tip = "群已解散"
                            onClose()
                        } else tip = "WS 未连接"
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ErrorDeep,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("解散群（仅群主）", fontSize = 10.sp) }
            Spacer(Modifier.height(4.dp))
        }
        DialogFooter(
            onCancel = onClose,
            confirmLabel = "关闭",
            onConfirm = onClose
        )
    }
    // 成员资料 overlay：点击成员后显示其资料 + 加好友
    selectedMember?.let { m ->
        MemberProfileOverlay(
            nickname = m.user.nickname,
            username = m.user.username,
            userId = m.user.id,
            avatarEmoji = m.user.avatarEmoji,
            avatarUrl = m.user.avatar,
            isSelf = currentUser?.id == m.userId,
            onAddFriend = {
                scope.launch {
                    val ok = app.webSocketClient.sendFriendRequest(m.user.id, "")
                    tip = if (ok) "已发送好友请求给 ${m.user.nickname.ifBlank { m.user.username }}" else "WS 未连接"
                    selectedMember = null
                }
            },
            onDismiss = { selectedMember = null }
        )
    }
    } // Box
}

@Composable
private fun GroupMemberRow(
    member: ApiClient.GroupMember,
    isSelf: Boolean,
    isOwnerView: Boolean,
    canManage: Boolean,
    onClick: () -> Unit,
    onKick: () -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onSetAdmin: () -> Unit,
    onRemoveAdmin: () -> Unit
) {
    val muted = member.mutedUntil > System.currentTimeMillis()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onClick() }
            ) {
                UserAvatar(
                    name = member.user.nickname.ifBlank { member.user.username },
                    size = 24.dp,
                    avatarEmoji = member.user.avatarEmoji,
                    avatarUrl = member.user.avatar
                )
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = member.user.nickname.ifBlank { member.user.username } + if (isSelf) "（我）" else "",
                        fontSize = 11.sp, fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = roleLabel(member.role) +
                            if (muted) " · 🔇禁言至 ${sdf.format(Date(member.mutedUntil))}" else "",
                        fontSize = 9.sp, color = if (muted) ErrorLight else OnSurfaceVariant
                    )
                }
            }
            // 管理按钮：仅管理员/群主可操作；不能对群主和自己操作
            val targetIsOwner = member.role == "owner"
            val canActOnTarget = canManage && !targetIsOwner && !isSelf &&
                (isOwnerView || member.role != "admin")  // 普通管理员不能管其他管理员
            if (canActOnTarget) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActionChipSmall("踢", ErrorDeep, onKick, Modifier.weight(1f))
                    if (muted) {
                        ActionChipSmall("解禁", MaterialTheme.colorScheme.primary, onUnmute, Modifier.weight(1f))
                    } else {
                        ActionChipSmall("禁言", PurpleDeep, onMute, Modifier.weight(1f))
                    }
                    if (isOwnerView) {
                        if (member.role == "admin") {
                            ActionChipSmall("撤管理", GrayOffline, onRemoveAdmin, Modifier.weight(1f))
                        } else {
                            ActionChipSmall("设管理", MaterialTheme.colorScheme.tertiary, onSetAdmin, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 用户资料页面：点击群成员/曝光板条目后全屏显示，带返回按钮和垂直滑动。
 * 通用于群成员管理与曝光板两个场景。
 */
@Composable
private fun MemberProfileOverlay(
    nickname: String,
    username: String,
    userId: String,
    avatarEmoji: String?,
    avatarUrl: String?,
    bio: String? = null,
    isSelf: Boolean = false,
    onAddFriend: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val displayName = nickname.ifBlank { username }.ifBlank { "用户" }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "用户资料", onBack = onDismiss)
        Spacer(Modifier.height(8.dp))
        // 头像 + 基本信息
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UserAvatar(
                name = displayName,
                size = 56.dp,
                avatarEmoji = avatarEmoji,
                avatarUrl = avatarUrl
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (username.isNotBlank()) {
                Text(text = "@$username", fontSize = 10.sp, color = OnSurfaceVariant)
            }
            Text(text = "ID: $userId", fontSize = 8.sp, color = OnSurfaceVariant)
        }
        // 简介
        if (!bio.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Text(
                    text = bio,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // 加好友按钮
        if (!isSelf && onAddFriend != null) {
            Button(
                onClick = onAddFriend,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                Text("加好友", fontSize = 11.sp)
            }
        } else if (isSelf) {
            Text(
                "（你自己）",
                fontSize = 10.sp,
                color = OnSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ActionChipSmall(label: String, bg: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun roleLabel(role: String): String = when (role) {
    "owner" -> "群主"
    "admin" -> "管理员"
    else -> "成员"
}

private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

// ==================== 公告板 ====================

@Composable
private fun AnnouncementsPanel(onClose: () -> Unit) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val currentUser by app.webSocketClient.currentUser.collectAsState()
    val isAdmin = currentUser?.role == "admin"

    var list by remember { mutableStateOf<List<ApiClient.Announcement>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 编辑/创建表单
    var editingId by remember { mutableStateOf<String?>(null) }   // null = 创建模式
    var showEditor by remember { mutableStateOf(false) }
    var eTitle by remember { mutableStateOf("") }
    var eContent by remember { mutableStateOf("") }
    var eType by remember { mutableStateOf("info") }

    // 加载公告
    suspend fun reload() {
        loading = true; error = null
        app.apiClient.getAnnouncements()
            .onSuccess { list = it }
            .onFailure { e -> error = "加载公告：${e.message}" }
        loading = false
    }
    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "公告板", onBack = onClose)
        Spacer(Modifier.height(6.dp))
        if (isAdmin) {
            Button(
                onClick = {
                    editingId = null; eTitle = ""; eContent = ""; eType = "info"
                    showEditor = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("＋ 新建公告", fontSize = 10.sp) }
            Spacer(Modifier.height(4.dp))
        }

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        } else if (error != null) {
            Text(error ?: "", color = ErrorLight, fontSize = 10.sp)
        } else if (list.isEmpty()) {
            Text("暂无公告", color = OnSurfaceVariant, fontSize = 10.sp,
                modifier = Modifier.padding(12.dp))
        } else {
            LazyColumn(
                modifier = Modifier.height(200.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(list) { a ->
                    AnnouncementCard(
                        ann = a,
                        isAdmin = isAdmin,
                        onEdit = {
                            editingId = a.id; eTitle = a.title; eContent = a.content; eType = a.type
                            showEditor = true
                        },
                        onDelete = {
                            scope.launch {
                                val token = app.preferenceManager.token.first()
                                if (token.isNullOrBlank()) { error = "未登录"; return@launch }
                                app.apiClient.deleteAnnouncement(token, a.id)
                                    .onSuccess { reload() }
                                    .onFailure { e -> error = "删除：${e.message}" }
                            }
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        DialogFooter(
            onCancel = onClose,
            confirmLabel = "关闭",
            onConfirm = onClose
        )
    }

    // 编辑/创建对话框
    if (showEditor) {
        Dialog(onDismissRequest = { showEditor = false }) {
            Card(shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(if (editingId == null) "新建公告" else "修改公告",
                        fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = eTitle, onValueChange = { eTitle = it },
                        label = { Text("标题", fontSize = 10.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = eContent, onValueChange = { eContent = it },
                        label = { Text("内容", fontSize = 10.sp) },
                        modifier = Modifier.fillMaxWidth().height(80.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("类型", fontSize = 10.sp, color = OnSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("info" to "信息", "warning" to "警告", "important" to "重要").forEach { (k, l) ->
                            FilterChip(
                                selected = eType == k,
                                onClick = { eType = k },
                                label = { Text(l, fontSize = 10.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { showEditor = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("取消", fontSize = 10.sp) }
                        Button(
                            onClick = {
                                scope.launch {
                                    val token = app.preferenceManager.token.first()
                                    if (token.isNullOrBlank()) { error = "未登录"; showEditor = false; return@launch }
                                    val r = if (editingId == null) {
                                        app.apiClient.createAnnouncement(token, eTitle, eContent, eType)
                                    } else {
                                        app.apiClient.updateAnnouncement(token, editingId!!, eTitle, eContent, eType)
                                    }
                                    r.onSuccess { showEditor = false; reload() }
                                      .onFailure { e -> error = "保存：${e.message}" }
                                }
                            },
                            enabled = eTitle.isNotBlank() && eContent.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("保存", fontSize = 10.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementCard(
    ann: ApiClient.Announcement,
    isAdmin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (badgeColor, badgeLabel) = when (ann.type) {
        "warning" -> Color(0xFFE65100) to "警告"
        "important" -> ErrorDeep to "重要"
        else -> MaterialTheme.colorScheme.primary to "信息"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(badgeColor)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) { Text(badgeLabel, color = Color.White, fontSize = 8.sp) }
                Spacer(Modifier.width(4.dp))
                Text(ann.title, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(2.dp))
            Text(ann.content, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text("发布：${ann.authorName} · ${sdf.format(Date(ann.createdAt))}",
                fontSize = 8.sp, color = OnSurfaceVariant)
            if (isAdmin) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = onEdit, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)) { Text("编辑", fontSize = 9.sp) }
                    Button(onClick = onDelete, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ErrorDeep),
                        contentPadding = PaddingValues(0.dp)) { Text("删除", fontSize = 9.sp) }
                }
            }
        }
    }
}

// ==================== 加群密码面板 ====================

@Composable
private fun JoinGroupPasswordPanel(
    group: ApiClient.SearchGroupResult,
    password: String,
    onPasswordChange: (String) -> Unit,
    inProgress: Boolean,
    resultMsg: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "加入群聊", onBack = onCancel)
        Spacer(Modifier.height(6.dp))
        Text(
            text = group.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        if (group.description.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = group.description,
                color = OnSurfaceVariant,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "#${group.groupNumber} · ${group.memberCount}人 · 🔒 需要密码",
            color = OnSurfaceVariant,
            fontSize = 9.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("加群密码", fontSize = 10.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            modifier = Modifier.fillMaxWidth()
        )
        resultMsg?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, color = if (it.contains("成功") || it.contains("已发送")) MaterialTheme.colorScheme.primary else ErrorLight, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("返回", fontSize = 11.sp) }
            Button(
                onClick = onConfirm,
                enabled = !inProgress && password.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 1.5.dp
                    )
                } else {
                    Text("确认加入", fontSize = 11.sp)
                }
            }
        }
    }
}

// ==================== 扫码登录面板 ====================

@Composable
private fun QrScannerPanel(
    scanResult: String?,
    scanError: String?,
    inProgress: Boolean,
    onScanSuccess: (String) -> Unit,
    onClose: () -> Unit
) {
    var manualInput by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    // zxing 扫码 launcher
    val scanLauncher = rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract()
    ) { result ->
        val content = result.contents
        if (!content.isNullOrBlank()) {
            onScanSuccess(content)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "扫码登录其他设备", onBack = onClose)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "扫描另一台设备上显示的登录二维码，确认其登录",
            color = OnSurfaceVariant,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))

        scanResult?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        scanError?.let {
            Text(it, color = ErrorLight, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
        }

        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp).align(Alignment.CenterHorizontally),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.height(6.dp))
        }

        if (scanResult == null && !inProgress) {
            Button(
                onClick = {
                    val options = com.journeyapps.barcodescanner.ScanOptions()
                        .setDesiredBarcodeFormats("QR_CODE")
                        .setBeepEnabled(true)
                        .setOrientationLocked(false)
                    scanLauncher.launch(options)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("开始扫码", fontSize = 12.sp)
            }
            Spacer(Modifier.height(4.dp))
            if (showManual) {
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    label = { Text("粘贴二维码内容", fontSize = 9.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        if (manualInput.isNotBlank()) {
                            onScanSuccess(manualInput.trim())
                            manualInput = ""
                        }
                    },
                    enabled = manualInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("确认", fontSize = 10.sp) }
            } else {
                Text(
                    text = "没有摄像头？手动输入",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showManual = true }
                        .padding(vertical = 4.dp),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("关闭", fontSize = 10.sp) }
    }
}

// ================================================================
// DPI / 显示密度切换（真正应用内 DPI，会同时影响 dp 像素密度和 sp 字体缩放）
// ================================================================
@Composable
private fun DpiSettingsPanel(onClose: () -> Unit) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val current by app.preferenceManager.dpiScale.collectAsState(initial = 1.0f)
    val screenShape by app.preferenceManager.screenShape.collectAsState(initial = "auto")

    val presets = listOf(0.75f, 0.85f, 0.95f, 1.0f, 1.1f, 1.2f, 1.35f)
    fun label(v: Float): String = when (v) {
        0.75f -> "特小"
        0.85f -> "紧凑"
        0.95f -> "偏小"
        1.0f -> "标准"
        1.1f -> "偏大"
        1.2f -> "大"
        1.35f -> "超大"
        else -> String.format(Locale.ROOT, "%.2f", v)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "显示密度 / DPI", onBack = onClose)
        Spacer(Modifier.height(3.dp))
        Text(
            "当前缩放：$current · 同时影响 dp（布局）和 sp（字体）。" +
                "保存后立即全局生效；如超出可见区域可再回到此处回调到 1.0 。",
            fontSize = 9.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(5.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            presets.forEach { v ->
                val selected = (v == current) ||
                    (v > current - 0.001f && v < current + 0.001f)
                FilterChip(
                    selected = selected,
                    onClick = {
                        scope.launch { app.preferenceManager.saveDpiScale(v) }
                    },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label(v), fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            Spacer(Modifier.weight(1f))
                            Text("x$v", fontSize = 9.sp, color = OnSurfaceVariant)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    colors = if (selected) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // 屏幕形状切换（方圆屏适配）
        Text(
            "屏幕形状",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "圆屏加宽边距防裁剪",
            fontSize = 8.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(3.dp))
        val shapes = listOf("auto" to "自动", "round" to "圆屏", "square" to "方屏")
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            shapes.forEach { (value, label) ->
                val sel = screenShape == value
                FilterChip(
                    selected = sel,
                    onClick = {
                        scope.launch { app.preferenceManager.saveScreenShape(value) }
                    },
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label, fontSize = 10.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                            Spacer(Modifier.weight(1f))
                            if (sel) Text("✓", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    colors = if (sel) {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            labelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            labelColor = MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("关闭", fontSize = 10.sp) }
    }
}

// ================================================================
// 曝光板（30min 限时曝光）：列表 + 我的资料 + 更新/续期/下架
// ================================================================
@Composable
private fun ExposureBoardPanel(onClose: () -> Unit) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()

    var list by remember { mutableStateOf<ApiClient.ExposureList?>(null) }
    var mine by remember { mutableStateOf<ApiClient.MyExposure?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 编辑我的资料
    var editing by remember { mutableStateOf(false) }
    var bioText by remember { mutableStateOf("") }
    var tagsText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var selectedExposure by remember { mutableStateOf<ApiClient.ExposureItem?>(null) }

    fun refresh() {
        scope.launch {
            loading = true; error = null
            val tok = app.preferenceManager.token.first()
            val listJob: Deferred<Result<ApiClient.ExposureList>> = async { app.apiClient.getExposureList(1, 30) }
            val mineJob: Deferred<Result<ApiClient.MyExposure>>? =
                if (tok.isNullOrBlank()) null else async { app.apiClient.getMyExposure(tok) }
            listJob.await().fold(
                onSuccess = { list = it },
                onFailure = { e -> error = e.message ?: "获取列表失败" }
            )
            mineJob?.await()?.fold(
                onSuccess = { mine = it },
                onFailure = { e -> if (error == null) error = "我的资料：${e.message}" }
            )
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            PageHeader(title = "曝光板", onBack = onClose)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { refresh() }) { Text("刷新", fontSize = 10.sp) }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "每位用户最多一份公开资料，发布/更新后 30 分钟在列表内自动曝光；过期后可重新「发布/续期」。",
            fontSize = 8.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        // 我的曝光卡片
        val m = mine
        if (m == null && loading) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        } else if (m != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (!m.onBoard || m.expired) PurpleDeep else PurpleLight
                )
            ) {
                Column(modifier = Modifier.padding(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when {
                                !m.onBoard -> "当前未上架"
                                m.expired -> "已过期（30min 已到）"
                                else -> "已上架 · 剩余 ${(m.expiresInMs / 60_000L)}m"
                            },
                            fontSize = 9.sp,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            bioText = m.bio.orEmpty()
                            tagsText = (m.tags).joinToString(",")
                            editing = true
                        }) { Text(if (m.onBoard) "编辑/续期" else "发布", fontSize = 9.sp, color = androidx.compose.ui.graphics.Color.White) }
                    }
                    if (m.onBoard) {
                        Spacer(Modifier.height(2.dp))
                        Text(m.bio.orEmpty().ifBlank { "（无简介）" }, fontSize = 9.sp, color = androidx.compose.ui.graphics.Color.White)
                        if (m.tags.isNotEmpty()) {
                            Spacer(Modifier.height(1.dp))
                            Text(m.tags.joinToString(" #", "#"), fontSize = 8.sp, color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f))
                        }
                        Spacer(Modifier.height(2.dp))
                        TextButton(
                            onClick = {
                                scope.launch {
                                    saving = true; error = null
                                    val tok = app.preferenceManager.token.first().orEmpty()
                                    app.apiClient.leaveExposure(tok).onFailure { e -> error = e.message ?: "下架失败" }
                                    saving = false
                                    refresh()
                                }
                            },
                            enabled = !saving
                        ) { Text("下架我的曝光", color = ErrorLight, fontSize = 8.sp) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // 编辑抽屉
        if (editing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
            ) {
                Column(modifier = Modifier.padding(5.dp)) {
                    Text("发布/续期（提交会自动续期 30min）", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    OutlinedTextField(
                        value = bioText,
                        onValueChange = { bioText = it.take(200) },
                        label = { Text("简介（≤200）", fontSize = 9.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(3.dp))
                    OutlinedTextField(
                        value = tagsText,
                        onValueChange = { tagsText = it },
                        label = { Text("标签（逗号分隔，最多 8 个，每个≤10）", fontSize = 9.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 10.sp)
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    if (bioText.isBlank()) {
                                        error = "简介不能为空"
                                        return@launch
                                    }
                                    saving = true; error = null
                                    val tags = tagsText.split(',', '，', ' ', ';', '；')
                                        .map { it.trim() }
                                        .filter { it.isNotBlank() }
                                        .take(8)
                                        .map { it.take(10) }
                                    val tok = app.preferenceManager.token.first().orEmpty()
                                    app.apiClient.upsertExposure(tok, bioText, tags).fold(
                                        onSuccess = { editing = false; refresh() },
                                        onFailure = { e -> error = e.message ?: "保存失败" }
                                    )
                                    saving = false
                                }
                            },
                            enabled = !saving && bioText.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (saving) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            else Text("发布 / 续期 30min", fontSize = 9.sp)
                        }
                        TextButton(onClick = { editing = false }) { Text("取消", fontSize = 9.sp) }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        error?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = ErrorDeep),
                modifier = Modifier.fillMaxWidth()
            ) { Text(it, color = ErrorLight, fontSize = 9.sp, modifier = Modifier.padding(5.dp)) }
            Spacer(Modifier.height(3.dp))
        }

        // 列表
        Text("公开列表（共 ${list?.total ?: 0}）", fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            }
        } else {
            val items = list?.list.orEmpty()
            if (items.isEmpty()) {
                Text("暂无曝光资料，来第一个发布吧～", fontSize = 9.sp, color = OnSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    items.take(30).forEach { e ->
                        Card(
                            onClick = { selectedExposure = e },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(modifier = Modifier.padding(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    UserAvatar(
                                        name = e.nickname.ifBlank { e.username },
                                        size = 22.dp,
                                        avatarEmoji = e.avatarEmoji,
                                        avatarUrl = e.avatar
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        e.nickname.ifBlank { e.username },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val remainMs = (e.expiresAt - System.currentTimeMillis())
                                        .coerceAtLeast(0L)
                                    val totalMin = remainMs / 60_000L
                                    Text("剩 ${totalMin}m", fontSize = 8.sp, color = OnSurfaceVariant)
                                }
                                if (e.bio.isNotBlank()) {
                                    Spacer(Modifier.height(1.dp))
                                    Text(e.bio, fontSize = 9.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                                if (e.tags.isNotEmpty()) {
                                    Spacer(Modifier.height(1.dp))
                                    Text(
                                        e.tags.joinToString(" #", "#"),
                                        fontSize = 8.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("关闭", fontSize = 10.sp) }
    }
    // 曝光板条目 overlay：点击条目后显示作者资料 + 加好友
    selectedExposure?.let { e ->
        MemberProfileOverlay(
            nickname = e.nickname,
            username = e.username,
            userId = e.userId,
            avatarEmoji = e.avatarEmoji,
            avatarUrl = e.avatar,
            bio = e.bio,
            isSelf = app.webSocketClient.currentUser.value?.id == e.userId,
            onAddFriend = {
                scope.launch {
                    val ok = app.webSocketClient.sendFriendRequest(e.userId, "")
                    error = if (ok) "已发送好友请求给 ${e.nickname.ifBlank { e.username }}" else "WS 未连接"
                    selectedExposure = null
                }
            },
            onDismiss = { selectedExposure = null }
        )
    }
    } // Box
}

// ================================================================
// 保活设置：常驻前台通知服务 + 申请忽略电池优化
// ================================================================
@Composable
private fun KeepAlivePanel(onClose: () -> Unit) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var keepEnabled by remember { mutableStateOf(app.preferenceManager.isKeepAliveCached) }
    var batteryOpted by remember { mutableStateOf<Boolean?>(null) }
    var notifGranted by remember { mutableStateOf<Boolean?>(null) }
    var log by remember { mutableStateOf<String?>(null) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { ok ->
        notifGranted = ok
        if (ok) {
            scope.launch {
                app.preferenceManager.setKeepAlive(true)
                app.ensureKeepAliveService(true)
                keepEnabled = true
                log = "已开启常驻通知保活"
            }
        } else {
            log = "通知权限被拒绝，无法开启前台通知保活"
        }
    }

    fun refreshChecks() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val ignore = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        batteryOpted = ignore
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            nm?.areNotificationsEnabled() ?: true
        }
    }
    LaunchedEffect(Unit) { refreshChecks() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
    ) {
        PageHeader(title = "保活设置", onBack = onClose)
        Spacer(Modifier.height(3.dp))
        Text(
            "为避免 Android 系统杀后台导致消息延迟：开启常驻前台通知 + 请求忽略电池优化。",
            fontSize = 8.sp,
            color = OnSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        // 1) 常驻通知保活
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("常驻通知保活", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "前台服务 + 持续通知栏图标，系统优先级最高，基本不会被杀。",
                    fontSize = 8.sp,
                    color = OnSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (keepEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .clickable {
                        scope.launch {
                            // 关闭则直接 stop 服务
                            if (keepEnabled) {
                                app.preferenceManager.setKeepAlive(false)
                                app.ensureKeepAliveService(false)
                                keepEnabled = false
                                log = "已关闭"
                                return@launch
                            }
                            // 开启：权限 + 启动
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                app.preferenceManager.setKeepAlive(true)
                                app.ensureKeepAliveService(true)
                                keepEnabled = true
                                log = "已开启常驻通知保活"
                                refreshChecks()
                            }
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    if (keepEnabled) "已开启" else "点击开启",
                    fontSize = 9.sp,
                    color = if (keepEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(3.dp))

        // 2) 通知权限
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("通知权限", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "用于前台服务通知栏和新消息提醒。",
                    fontSize = 8.sp,
                    color = OnSurfaceVariant
                )
            }
            Text(
                when (notifGranted) {
                    true -> "已授权"
                    false -> "未授权"
                    null -> "检测中"
                },
                color = if (notifGranted == true) MaterialTheme.colorScheme.primary else ErrorLight,
                fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(3.dp))

        // 3) 忽略电池优化
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("忽略电池优化（关闭电池优化）", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "避免系统在后台省电策略中断开 WebSocket。",
                    fontSize = 8.sp,
                    color = OnSurfaceVariant
                )
            }
            val isOk = batteryOpted == true
            if (!isOk) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary)
                        .clickable {
                            try {
                                val intent = android.content.Intent()
                                val pkg = context.packageName
                                if (batteryOpted == true) return@clickable
                                intent.action = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                intent.data = android.net.Uri.parse("package:$pkg")
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                (context as? Activity ?: return@clickable).startActivity(intent)
                                log = "请在打开的系统页面中选择「允许」"
                            } catch (t: Throwable) {
                                log = "跳转失败：${t.message}"
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "去开启",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSecondary
                    )
                }
            } else {
                Text("已加入白名单", color = GreenOnline, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(3.dp))
        // 刷新状态
        TextButton(onClick = { refreshChecks() }) { Text("重新检测状态", fontSize = 9.sp) }

        log?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, fontSize = 8.sp, color = OnSurfaceVariant)
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) { Text("关闭", fontSize = 10.sp) }
    }
}


