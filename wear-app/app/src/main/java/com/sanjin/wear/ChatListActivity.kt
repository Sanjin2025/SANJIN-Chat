// ==================== 主界面：消息列表 / 好友列表 / 设置入口 ====================
package com.sanjin.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjin.wear.model.*
import com.sanjin.wear.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 主界面 Activity
 * - Tab 1：消息列表（私聊+群聊 混合）
 * - Tab 2：好友列表（在线状态）
 * - Tab 3：更多（设置入口）
 * - 顶部状态栏：连接状态（绿/红/黄）
 * - 屏幕灭屏时断开 WS，亮屏重连（省电）
 */
class ChatListActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager
    private var server: String = ""
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        NotificationHelper.initChannel(this)

        val vm: MainViewModel by viewModels()

        setContent {
            var uiScale by remember { mutableStateOf(PreferencesManager.DEFAULT_SCALE) }
            LaunchedEffect(Unit) {
                uiScale = withContext(Dispatchers.IO) { prefs.getUiScale() }
            }
            SanjinWearTheme(uiScale = uiScale) {
                MainScreen(vm)
            }
        }

        // 初始化 WebSocket（登录页已经连了，这里补全）
        vm.viewModelScope.launch {
            token = withContext(Dispatchers.IO) { prefs.getToken() }
            server = withContext(Dispatchers.IO) { prefs.getServer() }
            if (token.isNotBlank() && server.isNotBlank()) {
                WebSocketManager.instance.connect(WearApp.instance.appScope, server, token)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WebSocketManager.instance.resume()
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing && !isChangingConfigurations) {
            WebSocketManager.instance.pause()
        }
    }

    // ================================================================
    // 主界面 UI
    // ================================================================
    @Composable
    fun MainScreen(vm: MainViewModel) {
        val scope = rememberCoroutineScope()
        val wsState by WebSocketManager.instance.state.collectAsState()
        var currentTab by rememberSaveable { mutableIntStateOf(0) }
        var me by remember { mutableStateOf<User?>(null) }

        var conversations by remember { mutableStateOf(emptyList<ConversationItem>()) }
        var friends by remember { mutableStateOf(emptyList<Friend>()) }
        var groups by remember { mutableStateOf(emptyList<GroupMembership>()) }
        var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
        var unreadMap by remember { mutableStateOf(mutableMapOf<String, Int>()) }
        var loading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            me = withContext(Dispatchers.IO) { prefs.getUser() }
            vm.loadAll(prefs, server, token) { r ->
                conversations = r.conv
                friends = r.friends
                groups = r.groups
                messages = r.messages
                unreadMap = r.unread
                loading = false
            }

            // 监听新消息：刷新会话 + 未读数 + 通知
            WebSocketManager.instance.chatMessages.collectLatest { m ->
                messages = messages + m
                val myId = me?.id ?: ""
                conversations = rebuildConversationList(friends, groups, messages, myId)
                if (m.fromId != me?.id) {
                    val key = chatKey(m)
                    unreadMap[key] = (unreadMap[key] ?: 0) + 1
                    NotificationHelper.vibrate(this@ChatListActivity)
                    val senderName = m.fromNickname ?: "新消息"
                    val (t, id) = if (m.type == "group_message")
                        ChatDetailActivity.TYPE_GROUP to (m.groupId ?: "")
                    else
                        ChatDetailActivity.TYPE_PRIVATE to (m.toId ?: m.fromId ?: "")
                    NotificationHelper.showNewMessage(this@ChatListActivity, m, senderName, t, id)
                }
            }
        }
        LaunchedEffect(Unit) {
            WebSocketManager.instance.friendsUpdate.collectLatest {
                vm.loadAll(prefs, server, token) { r -> friends = r.friends; groups = r.groups }
            }
        }
        LaunchedEffect(Unit) {
            WebSocketManager.instance.groupsUpdate.collectLatest {
                vm.loadAll(prefs, server, token) { r -> groups = r.groups }
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            TopBarWithStatus(wsState, me, onSettings = {
                startActivity(Intent(this@ChatListActivity, SettingsActivity::class.java))
            })

            TabRow(
                selectedTabIndex = currentTab,
                backgroundColor = Color.Transparent,
                contentColor = MaterialTheme.colors.primary
            ) {
                Tab(selected = currentTab == 0, onClick = { currentTab = 0 }) {
                    Text("💬 消息", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Tab(selected = currentTab == 1, onClick = { currentTab = 1 }) {
                    Text("👥 好友", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
                Tab(selected = currentTab == 2, onClick = { currentTab = 2 }) {
                    Text("⚙️ 更多", fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
                }
            }

            when (currentTab) {
                0 -> ConversationListTab(
                    loading = loading,
                    items = conversations,
                    unreadMap = unreadMap,
                    onClick = { conv ->
                        val key = if (conv.chatType == ChatType.PRIVATE)
                            "private:${conv.targetId}" else "group:${conv.targetId}"
                        unreadMap[key] = 0
                        val intent = Intent(this@ChatListActivity, ChatDetailActivity::class.java).apply {
                            putExtra(ChatDetailActivity.KEY_CHAT_TYPE,
                                if (conv.chatType == ChatType.PRIVATE)
                                    ChatDetailActivity.TYPE_PRIVATE else ChatDetailActivity.TYPE_GROUP
                            )
                            putExtra(ChatDetailActivity.KEY_TARGET_ID, conv.targetId)
                            putExtra(ChatDetailActivity.KEY_TITLE, conv.title)
                            putExtra(ChatDetailActivity.KEY_AVATAR_EMOJI, conv.avatarEmoji)
                        }
                        NotificationHelper.cancelAll(this@ChatListActivity)
                        startActivity(intent)
                    }
                )
                1 -> FriendsTab(
                    loading = loading,
                    friends = friends,
                    groups = groups,
                    onFriendClick = { f ->
                        startActivity(Intent(this@ChatListActivity, ChatDetailActivity::class.java).apply {
                            putExtra(ChatDetailActivity.KEY_CHAT_TYPE, ChatDetailActivity.TYPE_PRIVATE)
                            putExtra(ChatDetailActivity.KEY_TARGET_ID, f.userId)
                            putExtra(ChatDetailActivity.KEY_TITLE, f.user.displayName)
                            putExtra(ChatDetailActivity.KEY_AVATAR_EMOJI, f.user.displayEmoji)
                        })
                    },
                    onGroupClick = { g ->
                        startActivity(Intent(this@ChatListActivity, ChatDetailActivity::class.java).apply {
                            putExtra(ChatDetailActivity.KEY_CHAT_TYPE, ChatDetailActivity.TYPE_GROUP)
                            putExtra(ChatDetailActivity.KEY_TARGET_ID, g.group.id)
                            putExtra(ChatDetailActivity.KEY_TITLE, g.group.name)
                            putExtra(ChatDetailActivity.KEY_AVATAR_EMOJI, "👥")
                        })
                    }
                )
                2 -> MoreTab(me = me, wsState = wsState,
                    onSettings = {
                        startActivity(Intent(this@ChatListActivity, SettingsActivity::class.java))
                    },
                    onLogout = {
                        scope.launch {
                            withContext(Dispatchers.IO) { prefs.clearAll() }
                            WebSocketManager.instance.logout()
                            startActivity(Intent(this@ChatListActivity, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }
                    }
                )
            }
        }
    }

    // ================================================================
    // 子 Tab 组件
    // ================================================================
    @Composable
    fun TopBarWithStatus(wsState: WsState, me: User?, onSettings: () -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(me?.displayEmoji ?: "👤", size = 28, showOnline = true,
                online = wsState == WsState.CONNECTED)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(getString(R.string.home_title), style = MaterialTheme.typography.h6)
                Text(
                    text = when (wsState) {
                        WsState.CONNECTED -> getString(R.string.home_connected)
                        WsState.CONNECTING -> getString(R.string.home_connecting)
                        WsState.RECONNECTING -> "重连中…"
                        WsState.DISCONNECTED -> getString(R.string.home_disconnected)
                    },
                    color = when (wsState) {
                        WsState.CONNECTED -> WearColors.Online
                        WsState.CONNECTING, WsState.RECONNECTING -> Color(0xFFFF9800)
                        else -> WearColors.TextHint
                    },
                    fontSize = 11.sp
                )
            }
            IconButton(onClick = onSettings) { Text("⚙️", fontSize = 18.sp) }
        }
        com.sanjin.wear.ui.Divider()
    }

    @Composable
    fun ConversationListTab(
        loading: Boolean,
        items: List<ConversationItem>,
        unreadMap: Map<String, Int>,
        onClick: (ConversationItem) -> Unit
    ) {
        if (loading) { LoadingIndicator(); return }
        if (items.isEmpty()) { EmptyState(getString(R.string.home_empty)); return }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items) { conv ->
                val key = if (conv.chatType == ChatType.PRIVATE)
                    "private:${conv.targetId}" else "group:${conv.targetId}"
                val u = unreadMap[key] ?: 0
                com.sanjin.wear.ui.ConversationItem(
                    title = conv.title,
                    preview = conv.lastMessage,
                    time = formatTime(conv.lastTime),
                    avatarEmoji = conv.avatarEmoji,
                    unread = u,
                    online = conv.online,
                    onClick = { onClick(conv) }
                )
                com.sanjin.wear.ui.Divider()
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    @Composable
    fun FriendsTab(
        loading: Boolean,
        friends: List<Friend>,
        groups: List<GroupMembership>,
        onFriendClick: (Friend) -> Unit,
        onGroupClick: (GroupMembership) -> Unit
    ) {
        if (loading) { LoadingIndicator(); return }
        if (friends.isEmpty() && groups.isEmpty()) {
            EmptyState("暂无好友和群组"); return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (groups.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("👥 群聊", style = MaterialTheme.typography.subtitle1,
                            color = WearColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                items(groups) { g ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onGroupClick(g) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar("👥", size = 34)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(g.group.name, style = MaterialTheme.typography.subtitle1, maxLines = 1)
                            Text("${g.memberCount}人",
                                color = WearColors.TextSecondary,
                                style = MaterialTheme.typography.body2)
                        }
                    }
                }
            }
            if (friends.isNotEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("👬 好友", style = MaterialTheme.typography.subtitle1,
                            color = WearColors.TextSecondary, fontSize = 12.sp)
                    }
                }
                items(friends) { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFriendClick(f) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(f.user.displayEmoji, size = 34,
                            showOnline = true, online = f.online)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(f.user.displayName, style = MaterialTheme.typography.subtitle1, maxLines = 1)
                            Text(
                                if (f.online) getString(R.string.home_online) else getString(R.string.home_offline),
                                color = if (f.online) WearColors.Online else WearColors.TextSecondary,
                                style = MaterialTheme.typography.caption
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    @Composable
    fun MoreTab(
        me: User?,
        wsState: WsState,
        onSettings: () -> Unit,
        onLogout: () -> Unit
    ) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                WearCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me?.displayEmoji ?: "👤", size = 40,
                            showOnline = true, online = wsState == WsState.CONNECTED)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(me?.displayName ?: "未登录", style = MaterialTheme.typography.subtitle1)
                            Text("@${me?.username ?: ""}",
                                color = WearColors.TextSecondary,
                                style = MaterialTheme.typography.caption)
                        }
                    }
                }
            }
            item { WearCard { RowItem("⚙️", getString(R.string.settings_title), onClick = onSettings) } }
            item {
                WearCard {
                    RowItem("🔌", "连接状态: ${
                        when (wsState) {
                            WsState.CONNECTED -> "在线"
                            WsState.CONNECTING -> "连接中"
                            WsState.RECONNECTING -> "重连中"
                            else -> "离线"
                        }
                    }", onClick = null)
                }
            }
            item {
                WearCard {
                    RowItem("🚪", getString(R.string.settings_logout),
                        textColor = MaterialTheme.colors.error,
                        onClick = onLogout)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    @Composable
    fun RowItem(
        icon: String,
        text: String,
        textColor: Color = Color.White,
        onClick: (() -> Unit)?
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.body1, color = textColor)
        }
    }
}

// ================================================================
// 工具函数（包级，避免在 class 内互相依赖未解析）
// ================================================================

/** 根据 好友/群/消息 重建会话列表（按时间倒序） */
fun rebuildConversationList(
    friends: List<Friend>,
    groups: List<GroupMembership>,
    messages: List<ChatMessage>,
    myId: String
): List<ConversationItem> {
    val map = mutableMapOf<String, ConversationBuilder>()
    friends.forEach { f ->
        map["private:${f.userId}"] = ConversationBuilder(
            chatType = ChatType.PRIVATE,
            targetId = f.userId,
            title = f.user.displayName,
            avatarEmoji = f.user.displayEmoji,
            online = f.online
        )
    }
    groups.forEach { g ->
        map["group:${g.group.id}"] = ConversationBuilder(
            chatType = ChatType.GROUP,
            targetId = g.group.id,
            title = g.group.name,
            avatarEmoji = "👥",
            online = false
        )
    }
    messages.forEach { m ->
        val key = chatKey(m, myId)
        val b = map[key] ?: return@forEach
        if (b.lastTime <= m.timestamp) {
            b.lastTime = m.timestamp
            b.lastMessage = m.preview
        }
    }
    return map.values.map { it.build() }.sortedByDescending { it.lastTime }
}

/** ChatMessage -> Map key */
fun chatKey(m: ChatMessage, myId: String = ""): String {
    return if (m.type == "group_message")
        "group:${m.groupId}"
    else {
        val other = if (m.fromId == myId) (m.toId ?: "") else m.fromId
        "private:${other}"
    }
}

/** HH:mm */
fun formatTime(ts: Long): String {
    if (ts == 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return String.format("%02d:%02d",
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE))
}

// ================================================================
// ViewModel + DTO
// ================================================================

data class UpdateResult(
    val conv: List<ConversationItem>,
    val friends: List<Friend>,
    val groups: List<GroupMembership>,
    val messages: List<ChatMessage>,
    val unread: MutableMap<String, Int>
)

class MainViewModel : ViewModel() {
    suspend fun loadAll(
        prefs: PreferencesManager,
        serverIn: String,
        tokenIn: String,
        onUpdate: suspend (UpdateResult) -> Unit
    ) = withContext(Dispatchers.IO) {
        val s = serverIn.ifBlank { runCatching { prefs.getServer() }.getOrDefault(PreferencesManager.DEFAULT_SERVER) }
        val t = tokenIn.ifBlank { runCatching { prefs.getToken() }.getOrDefault("") }

        val friendsResult: List<Friend>
        val groupsResult: List<GroupMembership>
        val allMessages = mutableListOf<ChatMessage>()

        val d1 = viewModelScope.async {
            runCatching { ApiClient.getFriends(s, t).friends ?: emptyList() }.getOrDefault(emptyList())
        }
        val d2 = viewModelScope.async {
            runCatching { ApiClient.getGroups(s, t).groups ?: emptyList() }.getOrDefault(emptyList())
        }
        friendsResult = d1.await()
        groupsResult = d2.await()

        viewModelScope.launch {
            friendsResult.take(8).forEach { f ->
                runCatching { ApiClient.getPrivateMessages(s, t, f.userId).messages?.let { allMessages.addAll(it) } }
            }
            groupsResult.take(8).forEach { g ->
                runCatching { ApiClient.getGroupMessages(s, t, g.group.id).messages?.let { allMessages.addAll(it) } }
            }
        }.join()

        val myId = runCatching { prefs.getUser()?.id }.getOrNull() ?: ""
        val convList = rebuildConversationList(friendsResult, groupsResult, allMessages, myId)
        onUpdate(UpdateResult(convList, friendsResult, groupsResult, allMessages, mutableMapOf()))
    }
}
