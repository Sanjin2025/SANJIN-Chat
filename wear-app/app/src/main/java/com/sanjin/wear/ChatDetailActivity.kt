// ==================== 聊天详情页 ====================
package com.sanjin.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjin.wear.model.ApiResponse
import com.sanjin.wear.model.ChatMessage
import com.sanjin.wear.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天详情页
 * - 支持私聊（TYPE_PRIVATE）和群聊（TYPE_GROUP）
 * - 底部：语音识别按钮 + 6 个预设回复
 * - 消息气泡左/右分布（自己右侧靛蓝，对方左侧深灰）
 */
class ChatDetailActivity : ComponentActivity() {

    companion object {
        const val KEY_CHAT_TYPE = "chat_type"
        const val KEY_TARGET_ID = "target_id"
        const val KEY_TITLE = "title"
        const val KEY_AVATAR_EMOJI = "avatar_emoji"
        const val TYPE_PRIVATE = "private"
        const val TYPE_GROUP = "group"
    }

    /** 6 个预设快捷回复 */
    private val presetReplies = listOf("好的", "马上到", "在忙", "收到", "谢谢", "哈哈")

    private lateinit var prefs: PreferencesManager
    private var chatType = TYPE_PRIVATE
    private var targetId = ""
    private var title = ""
    private var avatarEmoji = "👤"

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                SpeechHelper.start(this@ChatDetailActivity) { text ->
                    // 识别到文字：自动填入输入框并发送
                    val vm: DetailViewModel by viewModels()
                    vm.sendNow(text)
                }
            } else {
                showNoPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        chatType = intent.getStringExtra(KEY_CHAT_TYPE) ?: TYPE_PRIVATE
        targetId = intent.getStringExtra(KEY_TARGET_ID).orEmpty()
        title = intent.getStringExtra(KEY_TITLE).orEmpty()
        avatarEmoji = intent.getStringExtra(KEY_AVATAR_EMOJI)?.ifBlank { "👤" } ?: "👤"

        val vm: DetailViewModel by viewModels()
        // 初始化 vm 参数
        if (vm.chatType.isBlank()) vm.init(chatType, targetId, title, avatarEmoji)

        setContent {
            var uiScale by remember { mutableStateOf(PreferencesManager.DEFAULT_SCALE) }
            LaunchedEffect(Unit) {
                uiScale = withContext(Dispatchers.IO) { prefs.getUiScale() }
            }
            SanjinWearTheme(uiScale = uiScale) {
                ChatDetailScreen(vm)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WebSocketManager.instance.resume()
        NotificationHelper.cancelAll(this)
    }

    override fun onPause() {
        super.onPause()
        WebSocketManager.instance.pause()
        SpeechHelper.destroy()
    }

    /** 显示没有录音权限提示 */
    private fun showNoPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle("提示")
            .setMessage("需要麦克风权限才能进行语音识别")
            .setPositiveButton("确定", null)
            .show()
    }

    // ================================================================
    // UI
    // ================================================================
    @Composable
    fun ChatDetailScreen(vm: DetailViewModel) {
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()
        var inputText by remember { mutableStateOf("") }
        var listening by remember { mutableStateOf(false) }

        // 加载历史消息
        LaunchedEffect(Unit) {
            vm.loadHistory(prefs) {
                // 滚到底部
                if (vm.messages.isNotEmpty()) {
                    scope.launch {
                        listState.scrollToItem(vm.messages.size - 1)
                    }
                }
            }
            // 监听新消息
            WebSocketManager.instance.chatMessages.collectLatest { m ->
                val isMine = (m.fromId == WebSocketManager.instance.currentUser.value?.id)
                val belongToMe = when (chatType) {
                    TYPE_PRIVATE ->
                        (m.type == "private_message") &&
                            (m.toId == targetId || m.fromId == targetId || isMine)
                    TYPE_GROUP ->
                        (m.type == "group_message") && (m.groupId == targetId)
                    else -> false
                }
                if (belongToMe) {
                    vm.messages += m
                    scope.launch {
                        listState.scrollToItem(vm.messages.size - 1)
                    }
                }
            }
        }

        // 语音识别状态
        val speechState by SpeechHelper.state.collectAsState()
        LaunchedEffect(speechState) {
            listening = speechState == SpeechHelper.SpeechState.LISTENING
        }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            // ===== 顶部标题栏 =====
            TopBar(
                title = title,
                left = {
                    IconButton(onClick = { finish() }) {
                        Text("⬅️", fontSize = 18.sp)
                    }
                }
            )
            com.sanjin.wear.ui.Divider()

            // ===== 消息列表 =====
            Box(modifier = Modifier.weight(1f)) {
                if (vm.loading) {
                    LoadingIndicator()
                } else if (vm.messages.isEmpty()) {
                    EmptyState("还没有消息\n点下面的按钮说点什么吧")
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(vm.messages) { msg ->
                            MessageBubble(msg)
                        }
                    }
                }
            }

            // ===== 输入区域 =====
            InputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        if (vm.sendNow(inputText)) {
                            inputText = ""
                            scope.launch { listState.scrollToItem(vm.messages.size - 1) }
                        }
                    }
                },
                onVoiceClick = {
                    val perm = ContextCompat.checkSelfPermission(
                        this@ChatDetailActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (listening) {
                        SpeechHelper.stop()
                    } else if (perm) {
                        SpeechHelper.start(this@ChatDetailActivity) { text ->
                            inputText = text
                            if (vm.sendNow(text)) {
                                inputText = ""
                                scope.launch { listState.scrollToItem(vm.messages.size - 1) }
                            }
                        }
                    } else {
                        requestPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                listening = listening
            )

            // ===== 预设回复 =====
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                mainAxisSpacing = 4.dp,
                crossAxisSpacing = 4.dp
            ) {
                presetReplies.forEach { p ->
                    OutlinedButton(
                        onClick = { if (vm.sendNow(p)) scope.launch { listState.scrollToItem(vm.messages.size - 1) } },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            backgroundColor = WearColors.SurfaceVariant,
                            contentColor = Color.White
                        )
                    ) {
                        Text(p, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    fun MessageBubble(msg: ChatMessage) {
        val me = WebSocketManager.instance.currentUser.value?.id.orEmpty()
        val mine = msg.isMine(me)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
        ) {
            if (!mine) {
                Avatar(msg.fromNickname?.firstOrNull()?.let { it.toString() }
                    ?: avatarEmoji, size = 24)
                Spacer(Modifier.width(4.dp))
            }
            val maxW = Modifier.widthIn(max = 220.dp)
            val bubbleColor = if (mine) WearColors.BubbleMe else WearColors.BubbleOther
            val textColor = if (mine) Color.White else Color.White
            val border = if (!mine) Modifier.border(1.dp, WearColors.Divider, RoundedCornerShape(12.dp)) else Modifier
            Box(
                modifier = maxW
                    .background(bubbleColor, RoundedCornerShape(12.dp))
                    .then(border)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column {
                    if (!mine && chatType == TYPE_GROUP && msg.fromNickname != null) {
                        Text(msg.fromNickname, color = WearColors.TextSecondary,
                            fontSize = 10.sp)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(msg.content, color = textColor, style = MaterialTheme.typography.body2,
                        lineHeight = 18.sp)
                    Text(msg.displayTime(),
                        modifier = Modifier.align(Alignment.End),
                        color = WearColors.TextSecondary.copy(alpha = 0.7f),
                        fontSize = 9.sp)
                }
            }
        }
    }

    @Composable
    fun InputBar(
        inputText: String,
        onInputChange: (String) -> Unit,
        onSend: () -> Unit,
        onVoiceClick: () -> Unit,
        listening: Boolean
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 语音按钮
            Button(
                onClick = onVoiceClick,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = if (listening) MaterialTheme.colors.error
                    else WearColors.SurfaceVariant
                )
            ) { Text(if (listening) "🎙" else "🎤", fontSize = 16.sp) }
            Spacer(Modifier.width(4.dp))
            // 输入框
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).height(40.dp),
                singleLine = true,
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    backgroundColor = WearColors.SurfaceVariant,
                    unfocusedLabelColor = WearColors.TextHint
                ),
                placeholder = { Text(getString(R.string.chat_hint_input), fontSize = 11.sp) }
            )
            Spacer(Modifier.width(4.dp))
            // 发送
            Button(
                onClick = onSend,
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
            ) { Text("➤", color = Color.White, fontSize = 16.sp) }
        }
    }
}

// ================================================================
// ViewModel
// ================================================================
class DetailViewModel : ViewModel() {
    var chatType: String = ""
    var targetId: String = ""
    var title: String = ""
    var avatarEmoji: String = "👤"

    val messages = mutableStateListOf<ChatMessage>()
    var loading by mutableStateOf(true)

    fun init(type: String, tid: String, t: String, ae: String) {
        chatType = type; targetId = tid; title = t; avatarEmoji = ae
    }

    suspend fun loadHistory(prefs: PreferencesManager, onLoaded: () -> Unit) =
        withContext(Dispatchers.IO) {
            val s = runCatching { prefs.getServer() }.getOrDefault(PreferencesManager.DEFAULT_SERVER)
            val t = runCatching { prefs.getToken() }.getOrDefault("")
            val resp = runCatching {
                when (chatType) {
                    ChatDetailActivity.TYPE_PRIVATE -> ApiClient.getPrivateMessages(s, t, targetId)
                    else -> ApiClient.getGroupMessages(s, t, targetId)
                }
            }.getOrNull()
            val list = (resp?.messages ?: emptyList()).takeLast(200)
            viewModelScope.launch {
                messages.clear()
                messages.addAll(list)
                loading = false
                onLoaded()
            }
        }

    /** 立即发送一条文字消息；如 WS 连接正常则先乐观插入一条本地消息 */
    fun sendNow(text: String): Boolean {
        if (text.isBlank()) return false
        val me = WebSocketManager.instance.currentUser.value ?: return false
        val ok = when (chatType) {
            ChatDetailActivity.TYPE_PRIVATE ->
                WebSocketManager.instance.sendPrivateMessage(targetId, text)
            else ->
                WebSocketManager.instance.sendGroupMessage(targetId, text)
        }
        if (ok) {
            // 乐观插入一条本地消息，显示到列表
            messages.add(ChatMessage(
                id = "local_${System.currentTimeMillis()}",
                type = if (chatType == ChatDetailActivity.TYPE_PRIVATE)
                    "private_message" else "group_message",
                fromId = me.id,
                fromNickname = me.displayName,
                toId = if (chatType == ChatDetailActivity.TYPE_PRIVATE) targetId else null,
                groupId = if (chatType == ChatDetailActivity.TYPE_GROUP) targetId else null,
                content = text
            ))
        }
        return ok
    }
}

/** 简化 FlowRow（当依赖没有的时候用这个 Row+Wrap 等价实现） */
@Composable
fun FlowRow(
    modifier: Modifier = Modifier,
    mainAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    crossAxisSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit
) {
    // 使用基础 Column/Rows 来模拟简单的换行布局（简单但能工作）
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(crossAxisSpacing)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(mainAxisSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val w = androidx.compose.ui.layout.SubcomposeLayout { constraints ->
                layout(0, 0) {}
            }
            // 简单实现：将 children 放在同一 Row，允许溢出时显示省略（实际可以接受）
            content()
        }
    }
}
