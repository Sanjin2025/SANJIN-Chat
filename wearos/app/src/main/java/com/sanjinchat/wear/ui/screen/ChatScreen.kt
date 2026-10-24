package com.sanjinchat.wear.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.audio.AudioPlayer
import com.sanjinchat.wear.audio.AudioRecorder
import com.sanjinchat.wear.data.MessageCache
import com.sanjinchat.wear.model.ChatMessage
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.network.ConnectionState
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.ExpressiveMotion
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import com.sanjinchat.wear.ui.theme.PurpleDeep
import com.sanjinchat.wear.ui.theme.PurpleLight
import com.sanjinchat.wear.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class ChatType {
    data class Private(val friendId: String, val friendName: String) : ChatType()
    data class Group(val groupId: String, val groupName: String) : ChatType()
}

private fun Uri.toLocalCacheFile(context: Context, prefix: String, suffix: String): File? {
    return runCatching {
        context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                c.moveToFirst()
                val name = if (nameIdx >= 0) c.getString(nameIdx) else "${prefix}_${System.currentTimeMillis()}"
                val target = File(context.cacheDir, "$prefix-${System.currentTimeMillis()}$suffix")
                context.contentResolver.openInputStream(this)?.use { inp ->
                    FileOutputStream(target).use { out -> inp.copyTo(out) }
                }
                // 以 suffix 决定扩展名（Uri 本身没有 ext 信息）
                val final = if (target.extension.isBlank()) File(target.parent, target.nameWithoutExtension + suffix) else target
                if (final != target) target.renameTo(final)
                final
            }
    }.getOrNull()
}

@Composable
fun ChatScreen(
    navController: NavController,
    chatType: ChatType
) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val currentUser by app.webSocketClient.currentUser.collectAsState()
    val connectionState by app.webSocketClient.connectionState.collectAsState()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var messageInput by remember { mutableStateOf("") }
    var sendingError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // 杀后台兜底：从 PreferenceManager 读取持久化 userId（currentUser 还没恢复时用）
    val savedUserId = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        savedUserId.value = app.preferenceManager.userId.first()
        // 确保 currentUser 可用：HTTP fallback（WS 连不上时也能用）
        if (currentUser == null) {
            val token = app.preferenceManager.token.first()
            if (!token.isNullOrBlank()) {
                app.apiClient.getMyProfile(token).onSuccess { user ->
                    if (app.webSocketClient.currentUser.value == null) {
                        app.webSocketClient.setCurrentUser(user)
                    }
                    savedUserId.value = user.id
                }
            }
        }
    }

    val chatTitle = when (chatType) {
        is ChatType.Private -> chatType.friendName
        is ChatType.Group -> chatType.groupName
    }

    // 进入聊天界面：标记当前会话，避免收到消息时弹出重复通知；并清除该会话已有通知
    val conversationTag = when (chatType) {
        is ChatType.Private -> chatType.friendId
        is ChatType.Group -> chatType.groupId
    }
    DisposableEffect(chatType) {
        app.activeConversation = conversationTag
        app.notificationHelper.cancel(conversationTag)
        onDispose {
            if (app.activeConversation == conversationTag) {
                app.activeConversation = null
            }
        }
    }

    // 进入时先从本地缓存加载（即时显示），再从 HTTP 拉取最新历史并合并
    var historyLoaded by remember(chatType) { mutableStateOf(false) }
    LaunchedEffect(chatType) {
        messages.clear()
        historyLoaded = false

        val convId = when (chatType) {
            is ChatType.Private -> MessageCache.privateConvId(chatType.friendId)
            is ChatType.Group -> MessageCache.groupConvId(chatType.groupId)
        }

        // 1. 先从本地缓存加载（秒开，不丢消息）
        val cached = app.messageCache.getMessages(convId)
        if (cached.isNotEmpty()) {
            messages.addAll(cached)
            runCatching { listState.animateScrollToItem(messages.size - 1) }
        }

        // 2. 再从 HTTP 拉取最新历史，合并去重后写回缓存
        val token = app.preferenceManager.token.first()
        if (!token.isNullOrBlank()) {
            val loaded = when (chatType) {
                is ChatType.Private -> app.apiClient.getPrivateMessages(token, chatType.friendId)
                is ChatType.Group -> app.apiClient.getGroupMessages(token, chatType.groupId)
            }
            loaded.onSuccess { list ->
                val existingIds = messages.map { it.id }.toMutableSet()
                for (msg in list) {
                    if (msg.id !in existingIds) {
                        messages.add(msg)
                        existingIds.add(msg.id)
                    }
                }
                messages.sortBy { it.timestamp }
                // HTTP 拉取的消息写回缓存
                app.messageCache.saveMessages(convId, list)
                if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
            }
        }
        historyLoaded = true
    }

    // 接收实时消息：死循环 collect，任何异常 1s 后重启；每次进入会话时，非 Connected 就兜底重连 WS
    LaunchedEffect(chatType, historyLoaded) {
        // 进入会话前兜底：如果 WS 还没 Connected（可能是杀后台后卡在 Connecting / Error），强制重连一次
        if (app.webSocketClient.connectionState.value !is ConnectionState.Connected) {
            val token = app.preferenceManager.token.first()
            if (!token.isNullOrBlank()) runCatching { app.webSocketClient.connect(token) }
        }
        while (true) {
            try {
                app.webSocketClient.events.collect { event ->
                    runCatching {
                        when (event) {
                            is ChatEvent.PrivateMessage -> {
                                if (chatType is ChatType.Private) {
                                    val msg = event.message
                                    val cu = currentUser?.id
                                    val isMyEcho = cu != null && msg.fromId == cu
                                    // 私聊归属判断（cu 可能为 null，此时只处理对方发来的）
                                    val isRelevant = if (cu != null) {
                                        (msg.fromId == chatType.friendId && msg.toId == cu) ||
                                        (isMyEcho && msg.toId == chatType.friendId)
                                    } else {
                                        msg.fromId == chatType.friendId
                                    }
                                    if (isRelevant) {
                                        if (isMyEcho) {
                                            val localIdx = messages.indexOfFirst {
                                                it.id.startsWith("local_") &&
                                                it.contentType == msg.contentType &&
                                                it.content == msg.content &&
                                                kotlin.math.abs(it.timestamp - msg.timestamp) < 15_000
                                            }
                                            if (localIdx >= 0) messages.removeAt(localIdx)
                                        }
                                        if (messages.none { it.id == msg.id }) {
                                            messages.add(msg)
                                            if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
                                        }
                                    }
                                }
                            }
                            is ChatEvent.GroupMessage -> {
                                if (chatType is ChatType.Group) {
                                    val msg = event.message
                                    if (msg.groupId == chatType.groupId) {
                                        val cu = currentUser?.id
                                        val isMyEcho = cu != null && msg.fromId == cu
                                        if (isMyEcho) {
                                            val localIdx = messages.indexOfFirst {
                                                it.id.startsWith("local_") &&
                                                it.contentType == msg.contentType &&
                                                it.content == msg.content &&
                                                kotlin.math.abs(it.timestamp - msg.timestamp) < 15_000
                                            }
                                            if (localIdx >= 0) messages.removeAt(localIdx)
                                        }
                                        if (messages.none { it.id == msg.id }) {
                                            messages.add(msg)
                                            if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
                                        }
                                    }
                                }
                            }
                            is ChatEvent.Error -> {
                                sendingError = event.message
                            }
                            else -> {}
                        }
                    }.onFailure { err ->
                        android.util.Log.w("ChatScreen", "事件处理异常：${err.message}")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("ChatScreen", "events.collect 中断，1s 后重启：${t.message}")
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // 图片选择器
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            sendingError = null
            val token = app.preferenceManager.token.first()
            if (token.isNullOrBlank()) { sendingError = "未登录"; return@launch }
            val file = withContext(Dispatchers.IO) { uri.toLocalCacheFile(context, "img", ".jpg") }
            if (file == null) { sendingError = "读取图片失败"; return@launch }
            val up = app.apiClient.uploadImage(token, file)
            up.onSuccess { (url) ->
                val ok = when (chatType) {
                    is ChatType.Private -> app.webSocketClient.sendPrivateImage(chatType.friendId, url)
                    is ChatType.Group -> app.webSocketClient.sendGroupImage(chatType.groupId, url)
                }
                if (ok) {
                    // 乐观更新：立即在本地显示图片消息
                    val cu = currentUser?.id ?: ""
                    val localMsg = ChatMessage(
                        id = "local_${System.currentTimeMillis()}",
                        type = when (chatType) {
                            is ChatType.Private -> "private_message"
                            is ChatType.Group -> "group_message"
                        },
                        fromId = cu,
                        fromNickname = currentUser?.nickname ?: "",
                        toId = if (chatType is ChatType.Private) chatType.friendId else null,
                        groupId = if (chatType is ChatType.Group) chatType.groupId else null,
                        contentType = "image",
                        content = "[图片]",
                        image = url,
                        timestamp = System.currentTimeMillis()
                    )
                    messages.add(localMsg)
                    runCatching { listState.animateScrollToItem(messages.size - 1) }
                } else sendingError = "发送图片失败"
            }.onFailure { e -> sendingError = "上传图片：${e.message}" }
        }
    }

    // ===== 语音播放：每条会话共用一个播放器（避免多条同时响） =====
    val audioPlayer = remember { AudioPlayer(context) }
    DisposableEffect(Unit) {
        onDispose { audioPlayer.release() }
    }

    // ===== 按住说话：录音 + 上传发送 =====
    val recorder = remember { AudioRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordSec by remember { mutableStateOf(0) }

    // 录音计时器
    LaunchedEffect(isRecording) {
        while (isRecording) {
            recordSec = (recorder.currentDurationMs() / 1000).toInt().coerceAtLeast(0)
            delay(200)
        }
        recordSec = 0
    }

    // 离开页面时确保释放录音资源
    DisposableEffect(Unit) {
        onDispose {
            if (recorder.isRecording()) recorder.cancel()
        }
    }

    // 运行时申请 RECORD_AUDIO 权限
    var hasRecordAudioPerm by remember {
        mutableStateOf(
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val reqRecordAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasRecordAudioPerm = granted }

    // 圆屏检测 + 适配 padding
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
    val hPadding = if (isRound) 14.dp else 6.dp
    val bottomPadding = if (isRound) 20.dp else 6.dp

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .animateContentSize(animationSpec = tween(200))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = { navController.popBackStack() },
                modifier = Modifier.size(40.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("←", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chatTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (chatType is ChatType.Group) "群聊" else "私聊",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无消息，发送第一条消息吧",
                        color = OnSurfaceVariant, textAlign = TextAlign.Center, fontSize = 11.sp,
                        modifier = Modifier
                            .padding(16.dp)
                            .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)),
                    contentPadding = PaddingValues(
                        start = hPadding, end = hPadding, top = 8.dp, bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { msg ->
                        // 杀后台兜底：三层判断确保"自己消息"不被渲染为对方
                        val myId = currentUser?.id ?: savedUserId.value
                        val isSelf = msg.id.startsWith("local_") ||
                            (myId != null && msg.fromId == myId)
                        MessageBubble(
                            message = msg,
                            isSelf = isSelf,
                            showSender = (chatType is ChatType.Group) && !isSelf,
                            mediaUrlResolver = { app.apiClient.mediaUrl(it) },
                            audioPlayer = audioPlayer
                        )
                    }
                }
            }

            // 录音中的浮层提示：Expressive 形变进入/退出
            androidx.compose.animation.AnimatedVisibility(
                visible = isRecording,
                enter = ExpressiveMotion.EnterExpand,
                exit = ExpressiveMotion.ExitShrink,
                modifier = Modifier.align(Alignment.Center),
                label = "recordingOverlay"
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xCC000000),
                            contentColor = Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Text(
                                text = "正在说话 ${recordSec}s",
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        // 错误提示：Expressive 滑入 + 形变
        AnimatedVisibility(
            visible = sendingError != null,
            enter = ExpressiveMotion.EnterSlideUp,
            exit = ExpressiveMotion.ExitSlideDown,
            modifier = Modifier.fillMaxWidth()
        ) {
            sendingError?.let { err ->
                Text(
                    text = err,
                    color = ErrorLight,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                )
            }
        }

        // 工具栏：图片 + 按住说话
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPadding, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Button(
                onClick = {
                    val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickImage.launch(Intent.createChooser(i, "选择图片"))
                },
                modifier = Modifier.weight(1f).height(32.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("🖼 图片", fontSize = 10.sp) }
            // 按住说话：detectTapGestures 监听按下/松开
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isRecording) PurpleDeep else SurfaceVariant)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                // 按下：申请权限 + 开始录音
                                if (!hasRecordAudioPerm) {
                                    reqRecordAudio.launch(android.Manifest.permission.RECORD_AUDIO)
                                    // 等待释放，不开始录音
                                    tryAwaitRelease()
                                } else {
                                    val started = recorder.start()
                                    if (started) {
                                        isRecording = true
                                    } else {
                                        Toast.makeText(context, "无法启动录音", Toast.LENGTH_SHORT).show()
                                    }
                                    // 等待松开
                                    tryAwaitRelease()
                                    // 松开：停止录音并发送
                                    if (isRecording) {
                                        isRecording = false
                                        val result = recorder.stop()
                                        if (result == null) {
                                            Toast.makeText(context, "录音太短", Toast.LENGTH_SHORT).show()
                                        } else {
                                            scope.launch {
                                                sendingError = null
                                                val token = app.preferenceManager.token.first()
                                                if (token.isNullOrBlank()) {
                                                    sendingError = "未登录"
                                                    return@launch
                                                }
                                                val durSec = ((result.durationMs + 999) / 1000).toInt().coerceAtLeast(1)
                                                val up = app.apiClient.uploadAudio(token, result.file, durSec)
                                                up.onSuccess { uf ->
                                                    val audioJson = JSONObject().apply {
                                                        put("url", uf.url)
                                                        put("duration", uf.durationSec)
                                                    }.toString()
                                                    val ok = when (chatType) {
                                                        is ChatType.Private -> app.webSocketClient.sendPrivateAudio(
                                                            chatType.friendId, uf.url, uf.durationSec
                                                        )
                                                        is ChatType.Group -> app.webSocketClient.sendGroupAudio(
                                                            chatType.groupId, uf.url, uf.durationSec
                                                        )
                                                    }
                                                    if (ok) {
                                                        // 乐观更新：立即在本地显示语音消息，不等待服务端回显
                                                        val cu = currentUser?.id ?: ""
                                                        val localMsg = ChatMessage(
                                                            id = "local_${System.currentTimeMillis()}",
                                                            type = when (chatType) {
                                                                is ChatType.Private -> "private_message"
                                                                is ChatType.Group -> "group_message"
                                                            },
                                                            fromId = cu,
                                                            fromNickname = currentUser?.nickname ?: "",
                                                            toId = if (chatType is ChatType.Private) chatType.friendId else null,
                                                            groupId = if (chatType is ChatType.Group) chatType.groupId else null,
                                                            contentType = "audio",
                                                            content = "[语音 ${uf.durationSec}s]",
                                                            audio = audioJson,
                                                            timestamp = System.currentTimeMillis()
                                                        )
                                                        messages.add(localMsg)
                                                        runCatching { listState.animateScrollToItem(messages.size - 1) }
                                                    } else {
                                                        sendingError = "发送语音失败"
                                                    }
                                                }.onFailure { e -> sendingError = "上传语音：${e.message}" }
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isRecording) "松开 发送" else "按住 说话",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isRecording) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPadding, vertical = 3.dp)
                .padding(bottom = bottomPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                label = { Text("消息", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(4.dp))
            Button(
                onClick = {
                    val text = messageInput.trim()
                    if (text.isNotBlank()) {
                        scope.launch {
                            sendingError = null
                            // 杀后台兜底：WS 已断开时自动重连再发送
                            val token = app.preferenceManager.token.first()
                            if (connectionState == com.sanjinchat.wear.network.ConnectionState.Disconnected
                                && !token.isNullOrBlank()) {
                                app.webSocketClient.connect(token)
                                // 等待最多 3 秒等连接
                                repeat(30) {
                                    kotlinx.coroutines.delay(100)
                                    if (app.webSocketClient.connectionState.value
                                        is com.sanjinchat.wear.network.ConnectionState.Connected) return@repeat
                                }
                            }
                            val cu = currentUser?.id ?: savedUserId.value ?: ""
                            val sent = when (chatType) {
                                is ChatType.Private -> app.webSocketClient.sendPrivateMessage(chatType.friendId, text)
                                is ChatType.Group -> app.webSocketClient.sendGroupMessage(chatType.groupId, text)
                            }
                            if (sent) {
                                messageInput = ""
                                // 乐观更新：立即在本地显示文字消息
                                val localMsg = ChatMessage(
                                    id = "local_${System.currentTimeMillis()}",
                                    type = when (chatType) {
                                        is ChatType.Private -> "private_message"
                                        is ChatType.Group -> "group_message"
                                    },
                                    fromId = cu,
                                    fromNickname = currentUser?.nickname ?: "",
                                    toId = if (chatType is ChatType.Private) chatType.friendId else null,
                                    groupId = if (chatType is ChatType.Group) chatType.groupId else null,
                                    contentType = "text",
                                    content = text,
                                    timestamp = System.currentTimeMillis()
                                )
                                messages.add(localMsg)
                                runCatching { listState.animateScrollToItem(messages.size - 1) }
                                // 写入缓存（杀后台也不丢）
                                val convId = when (chatType) {
                                    is ChatType.Private -> MessageCache.privateConvId(chatType.friendId)
                                    is ChatType.Group -> MessageCache.groupConvId(chatType.groupId)
                                }
                                app.messageCache.saveMessage(convId, localMsg)
                            } else sendingError = "WebSocket 未连接，请等待重连"
                        }
                    }
                },
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = messageInput.isNotBlank()
            ) { Text("➤", fontSize = 18.sp) }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ChatMessage,
    isSelf: Boolean,
    showSender: Boolean,
    mediaUrlResolver: (String) -> String?,
    audioPlayer: AudioPlayer
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
    ) {
        if (showSender) {
            Text(
                text = message.fromNickname,
                color = OnSurfaceVariant,
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }
        val bg = if (isSelf) PurpleLight else SurfaceVariant
        val fg = if (isSelf) PurpleDeep else MaterialTheme.colorScheme.onSurface
        Card(
            modifier = Modifier
                .width(width = 200.dp)
                .clip(RoundedCornerShape(12.dp)),
            colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                when (message.contentType) {
                    "image" -> {
                        val rawUrl = message.image?.takeUnless { it.isNullOrBlank() }
                            ?: message.content?.takeIf { it.startsWith("http") || it.startsWith("/") }
                        val url = rawUrl?.let { mediaUrlResolver(it) }
                        if (url != null) {
                            val ctx = LocalContext.current
                            AsyncImage(
                                model = ImageRequest.Builder(ctx)
                                    .data(url)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "图片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(text = "🖼 图片", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                    "audio" -> {
                        val payload = runCatching { JSONObject(message.audio ?: "{}") }.getOrNull()
                        val rawUrl = payload?.optString("url")?.takeUnless { it.isNullOrBlank() }
                            ?: message.audio?.takeUnless { it.isNullOrBlank() }
                        val resolved = rawUrl?.let { mediaUrlResolver(it) }
                        // 声明/总时长（秒）：优先服务端给的 duration，否则播放时再更新
                        val declaredDurSec = payload?.optInt("duration")?.takeIf { it > 0 }

                        // 订阅播放器状态：只有当前播放 url == resolved 时才显示播放中的 UI
                        val playerUrl by audioPlayer.currentUrl.collectAsState()
                        val isPlayingThis by audioPlayer.isPlaying.collectAsState()
                        val playingNow = isPlayingThis && (playerUrl == resolved)
                        val posMs by audioPlayer.positionMs.collectAsState()
                        val durMs by audioPlayer.durationMs.collectAsState()

                        val (curPos, totalDur) = when (playerUrl == resolved && durMs > 0L) {
                            true -> posMs to durMs
                            false -> 0L to (declaredDurSec?.times(1000L) ?: 0L)
                        }
                        val progressFrac =
                            if (totalDur > 0L) (curPos.toFloat() / totalDur.toFloat()).coerceIn(0f, 1f)
                            else 0f

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (resolved != null) {
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(enabled = true, onClick = { audioPlayer.toggle(resolved) })
                                    .padding(horizontal = 2.dp, vertical = 2.dp)
                            } else Modifier
                        ) {
                            // 播放/暂停按钮
                            Text(
                                text = when {
                                    resolved == null -> "🔇"
                                    playingNow -> "⏸"
                                    else -> "▶"
                                },
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                // 时长 + 状态
                                val seconds = (totalDur / 1000L).toInt().coerceAtLeast(0)
                                Text(
                                    text = buildString {
                                        append(if (seconds > 0) "${seconds}\"" else "语音")
                                        if (playingNow) append(" · 播放中")
                                        else if (resolved == null) append(" · 加载失败")
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(3.dp))
                                // 进度条
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(
                                            androidx.compose.ui.graphics.Color.White.copy(alpha = 0.3f)
                                        )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progressFrac)
                                            .height(3.dp)
                                            .background(
                                                if (isSelf) PurpleDeep
                                                else MaterialTheme.colorScheme.primary
                                            )
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Text(
                            text = message.content.ifBlank { "（空消息）" },
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = formatTime(message.timestamp),
                    fontSize = 7.sp,
                    color = OnSurfaceVariant,
                    textAlign = if (isSelf) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private val sdf by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }
private fun formatTime(ts: Long): String =
    if (ts <= 0L) "" else sdf.format(Date(ts))
