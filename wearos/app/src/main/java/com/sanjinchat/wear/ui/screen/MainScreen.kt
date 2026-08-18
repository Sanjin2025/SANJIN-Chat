package com.sanjinchat.wear.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.model.Friend
import com.sanjinchat.wear.model.FriendRequest
import com.sanjinchat.wear.model.GroupMembership
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.network.ConnectionState
import com.sanjinchat.wear.network.Diagnostics
import com.sanjinchat.wear.network.ProbeResult
import com.sanjinchat.wear.ui.theme.ErrorDeep
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.GrayOffline
import com.sanjinchat.wear.ui.theme.GreenOnline
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import com.sanjinchat.wear.ui.theme.PurpleDeep
import com.sanjinchat.wear.ui.theme.PurpleLight
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

enum class MainTab { CHATS, FRIENDS, GROUPS, SETTINGS }

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

    // 进入页面：应用服务器偏好 → 连 WS → 拉取好友/群/请求列表（HTTP，保证即使 WS 推送延迟也有数据）
    LaunchedEffect(Unit) {
        val token = app.preferenceManager.token.first()
        val serverAddr = app.preferenceManager.serverAddress.first()
        val protocol = app.preferenceManager.serverProtocol.first()
        if (serverAddr.isNotBlank()) {
            app.apiClient.serverAddress = serverAddr
            app.webSocketClient.serverAddress = serverAddr
        }
        app.apiClient.serverProtocol = protocol
        app.webSocketClient.serverProtocol = protocol
        if (!token.isNullOrBlank()) {
            // 先拉 HTTP：好友 / 好友请求 / 群
            listOf(
                launch {
                    app.apiClient.getFriends(token)
                        .onSuccess { friends -> app.webSocketClient.friends.value = friends }
                },
                launch {
                    app.apiClient.getFriendRequests(token)
                        .onSuccess { reqs -> app.webSocketClient.friendRequests.value = reqs }
                },
                launch {
                    app.apiClient.getGroups(token)
                        .onSuccess { groups -> app.webSocketClient.groups.value = groups }
                }
            ).joinAll()
            if (connectionState is ConnectionState.Disconnected) {
                app.webSocketClient.connect(token)
            }
        }
    }

    // -------- Dialog 状态 --------
    var dialogKind by remember { mutableStateOf(DialogKind.None) }
    // 添加好友
    var addTarget by remember { mutableStateOf("") }
    var addMessage by remember { mutableStateOf("") }
    // 选定项
    var pendingFriend by remember { mutableStateOf<Friend?>(null) }
    var pendingGroup by remember { mutableStateOf<GroupMembership?>(null) }
    // 创建群
    var createName by remember { mutableStateOf("") }
    var createDesc by remember { mutableStateOf("") }
    var createPassword by remember { mutableStateOf("") }
    // 加入群
    var joinNumber by remember { mutableStateOf("") }
    var joinPassword by remember { mutableStateOf("") }
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

    // 配对码倒计时：info 变化时重置 ticker，每秒递减
    LaunchedEffect(pairCodeInfo) {
        val info = pairCodeInfo
        if (info == null) {
            pairCountdown = 0
        } else {
            while (true) {
                val remaining = (info.expiresAt - System.currentTimeMillis()) / 1000L
                pairCountdown = remaining.toInt().coerceAtLeast(0)
                if (remaining <= 0L) break
                delay(1000L)
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
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top status bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = connText,
                    color = OnSurfaceVariant,
                    fontSize = 9.sp
                )
                currentUser?.let { user ->
                    Text(
                        text = user.nickname.ifBlank { user.username },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
                    label = "设置",
                    sym = "⚙",
                    selected = currentTab == MainTab.SETTINGS,
                    onClick = { currentTab = MainTab.SETTINGS },
                    modifier = Modifier.weight(1f)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 6.dp,
                    end = 6.dp,
                    top = 6.dp,
                    bottom = 40.dp
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
                                ChatListItem(
                                    name = friend.user.nickname.ifBlank { friend.user.username },
                                    subtitle = if (friend.online) "在线" else "离线",
                                    isOnline = friend.online,
                                    onClick = {
                                        val displayName = friend.user.nickname.ifBlank { friend.user.username }
                                        navController.navigate("chat/private/${friend.userId}/${displayName}")
                                    }
                                )
                            }
                            items(groups) { group ->
                                ChatListItem(
                                    name = group.group.name,
                                    subtitle = "${group.memberCount}人 · #${group.group.groupNumber}",
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
                            items(friends) { friend ->
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
                                        createName = ""
                                        createDesc = ""
                                        createPassword = ""
                                        dialogKind = DialogKind.CreateGroup
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                ActionChip(
                                    label = "加入群聊",
                                    sym = "🔑",
                                    onClick = {
                                        joinNumber = ""
                                        joinPassword = ""
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
                                    }
                                )
                            }
                        }
                    }

                    MainTab.SETTINGS -> {
                        item { SectionHeader("设置") }
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
                                dpiScale <= 0.9f -> "紧凑 ($dpiScale)"
                                dpiScale >= 1.25f -> "超大 ($dpiScale)"
                                dpiScale > 1.05f -> "大 ($dpiScale)"
                                else -> "标准 ($dpiScale)"
                            }
                            SettingsCard(title = "字体大小", content = dpiLabel)
                        }
                        item {
                            // 生成 6 位配对码（2 分钟有效），让其他设备扫码/输入后免密登录此账号
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        val token = app.preferenceManager.token.first()
                                        if (token.isNullOrBlank()) {
                                            pairError = "未登录"
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
                                                        onFailure = { pairError = it.message }
                                                    )
                                                }
                                            },
                                            onFailure = { cacheErr ->
                                                app.apiClient.generatePairCode(token).fold(
                                                    onSuccess = { pairCodeInfo = it },
                                                    onFailure = { pairError = cacheErr.message ?: it.message }
                                                )
                                            }
                                        )
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
                            // 高级设置：服务器地址 + ws/wss
                            AssistChip(
                                onClick = {
                                    scope.launch {
                                        advAddress = app.preferenceManager.serverAddress.first()
                                            .takeUnless { it.isBlank() } ?: "your-server.com:8181"
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
                                        val addr = app.preferenceManager.serverAddress.first().takeUnless { it.isBlank() }
                                            ?: "your-server.com:8181"
                                        val proto = app.preferenceManager.serverProtocol.first().takeUnless { it.isBlank() }
                                            ?: "ws"
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
                                        app.preferenceManager.resetOnboarding()
                                        navController.navigate("onboarding") {
                                            popUpTo("main") { inclusive = true }
                                        }
                                    }
                                },
                                label = { Text("重新进行首次设置", color = OnSurfaceVariant) },
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
                    }
                }
            }
        }

        // ===== Dialogs =====
        if (dialogKind != DialogKind.None) {
            Dialog(onDismissRequest = { dialogKind = DialogKind.None }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(10.dp)
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
                            name = createName,
                            desc = createDesc,
                            password = createPassword,
                            onNameChange = { createName = it },
                            onDescChange = { createDesc = it },
                            onPasswordChange = { createPassword = it },
                            onCancel = { dialogKind = DialogKind.None },
                            onConfirm = {
                                scope.launch {
                                    val ok = app.webSocketClient.createGroup(
                                        name = createName.trim(),
                                        description = createDesc.trim(),
                                        password = createPassword.ifBlank { null }
                                    )
                                    if (ok) dialogKind = DialogKind.None
                                }
                            }
                        )
                        DialogKind.JoinGroup -> JoinGroupPanel(
                            number = joinNumber,
                            password = joinPassword,
                            onNumberChange = { joinNumber = it },
                            onPasswordChange = { joinPassword = it },
                            onCancel = { dialogKind = DialogKind.None },
                            onConfirm = {
                                scope.launch {
                                    val ok = app.webSocketClient.joinGroup(
                                        joinNumber.trim(), joinPassword
                                    )
                                    if (ok) dialogKind = DialogKind.None
                                }
                            }
                        )
                        DialogKind.LeaveGroupConfirm -> ConfirmPanel(
                            title = "离开群聊",
                            desc = "确定离开「${pendingGroup?.group?.name ?: "此群"}」？",
                            confirmLabel = "离开",
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
                                    pairError = null
                                    val token = app.preferenceManager.token.first()
                                    if (token.isNullOrBlank()) {
                                        pairError = "未登录"
                                        return@launch
                                    }
                                    val genResult = app.apiClient.generatePairCode(token)
                                    genResult.fold(
                                        onSuccess = { pairCodeInfo = it },
                                        onFailure = { pairError = it.message }
                                    )
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
                                        advError = "请输入服务器地址"
                                        return@launch
                                    }
                                    val proto = if (advProtocol == "wss") "wss" else "ws"
                                    app.preferenceManager.saveServerAddress(addr)
                                    app.preferenceManager.saveServerProtocol(proto)
                                    app.apiClient.serverAddress = addr
                                    app.apiClient.serverProtocol = proto
                                    app.webSocketClient.serverAddress = addr
                                    app.webSocketClient.serverProtocol = proto
                                    val token = app.preferenceManager.token.first()
                                    if (!token.isNullOrBlank()) {
                                        app.webSocketClient.disconnect()
                                        app.webSocketClient.connect(token)
                                    }
                                    dialogKind = DialogKind.None
                                }
                            }
                        )
                        DialogKind.UserSearch -> UserSearchPanel(
                            kind = searchKind,
                            onKindChange = { searchKind = it; searchUserResults = emptyList(); searchGroupResults = emptyList() },
                            keyword = searchKeyword,
                            onKeywordChange = { searchKeyword = it },
                            running = searchRunning,
                            userResults = searchUserResults,
                            groupResults = searchGroupResults,
                            error = searchError,
                            addMessage = searchAddMsg,
                            onAddMessageChange = { searchAddMsg = it },
                            joinPassword = searchJoinPwd,
                            onJoinPasswordChange = { searchJoinPwd = it },
                            onSearch = {
                                scope.launch {
                                    searchRunning = true
                                    searchError = null
                                    val token = app.preferenceManager.token.first()
                                    if (token.isNullOrBlank()) {
                                        searchError = "未登录"
                                        searchRunning = false
                                        return@launch
                                    }
                                    if (searchKind == "user") {
                                        val r = app.apiClient.searchUsers(token, searchKeyword)
                                        r.onSuccess { searchUserResults = it; searchGroupResults = emptyList() }
                                            .onFailure { searchError = it.message ?: "搜索失败" }
                                    } else {
                                        val r = app.apiClient.searchGroups(token, searchKeyword)
                                        r.onSuccess { searchGroupResults = it; searchUserResults = emptyList() }
                                            .onFailure { searchError = it.message ?: "搜索失败" }
                                    }
                                    searchRunning = false
                                }
                            },
                            onAddFriend = { u ->
                                val message = searchAddMsg.ifBlank { "你好，我是${currentUser?.nickname?.takeIf { it.isNotBlank() } ?: currentUser?.username}" }
                                val sent = app.webSocketClient.sendFriendRequest(u.id, message)
                                if (sent) dialogKind = DialogKind.None else searchError = "WebSocket 未连接"
                            },
                            onJoinGroup = { g ->
                                val ok = app.webSocketClient.joinGroup(g.groupNumber, searchJoinPwd)
                                if (ok) dialogKind = DialogKind.None else searchError = "WebSocket 未连接"
                            },
                            onClose = { dialogKind = DialogKind.None }
                        )
                        DialogKind.None -> { /* unreachable */ }
                    }
                }
            }
        }
    }
}

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
    joinPassword: String,
    onJoinPasswordChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAddFriend: (ApiClient.SearchUserResult) -> Unit,
    onJoinGroup: (ApiClient.SearchGroupResult) -> Unit,
    onClose: () -> Unit
) {
    Column {
        DialogTitle(if (kind == "user") "查找用户" else "查找群聊")
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
        } else {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = joinPassword,
                onValueChange = onJoinPasswordChange,
                label = { Text("加群密码（无密码留空）", fontSize = 10.sp) },
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
    Column {
        DialogTitle("添加好友")
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
    Column {
        DialogTitle("好友请求")
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
                    val name = (if (isIncoming) req.fromUser else req.toUser)
                        ?.let { it.nickname.ifBlank { it.username } } ?: "用户"
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(6.dp)
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                UserAvatar(name = name, size = 24.dp)
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
    Column {
        DialogTitle("创建群聊")
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
    Column {
        DialogTitle("加入群聊")
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

    Column {
        DialogTitle("配对码登录")
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

        if (code != null) {
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
                    text = if (error == null) "生成中..." else "生成失败",
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
    Column {
        DialogTitle("高级设置")
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
            text = "示例：your-server.com:8181",
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
    Column {
        DialogTitle("连接诊断")
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
        modifier = Modifier.padding(vertical = 4.dp, horizontal = 2.dp)
    )
}

@Composable
private fun ChatListItem(
    name: String,
    subtitle: String,
    isOnline: Boolean = false,
    isGroup: Boolean = false,
    onClick: () -> Unit
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
                .padding(6.dp)
        ) {
            UserAvatar(name = name, size = 26.dp, isGroup = isGroup)
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
                size = 28.dp
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
    onLeave: () -> Unit
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
    isGroup: Boolean = false
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
        Text(
            text = initial.toString(),
            fontSize = (size.value * 0.48).sp,
            color = if (isGroup) PurpleLight else Color.White,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
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
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(7.dp)
        ) {
            Text(
                text = title,
                color = OnSurfaceVariant,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = content,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp
            )
        }
    }
}
