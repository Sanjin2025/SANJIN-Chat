package com.sanjinchat.wear.ui.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.model.ChatMessage
import com.sanjinchat.wear.network.ChatEvent
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import com.sanjinchat.wear.ui.theme.PurpleDeep
import com.sanjinchat.wear.ui.theme.PurpleLight
import com.sanjinchat.wear.ui.theme.SurfaceVariant
import kotlinx.coroutines.Dispatchers
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

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var messageInput by remember { mutableStateOf("") }
    var sendingError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    val chatTitle = when (chatType) {
        is ChatType.Private -> chatType.friendName
        is ChatType.Group -> chatType.groupName
    }

    // 进入时拉取历史消息（HTTP /messages/private/:friendId 或 /messages/group/:groupId）
    var historyLoaded by remember(chatType) { mutableStateOf(false) }
    LaunchedEffect(chatType) {
        messages.clear()
        historyLoaded = false
        val token = app.preferenceManager.token.first()
        if (!token.isNullOrBlank()) {
            val loaded = when (chatType) {
                is ChatType.Private -> app.apiClient.getPrivateMessages(token, chatType.friendId)
                is ChatType.Group -> app.apiClient.getGroupMessages(token, chatType.groupId)
            }
            loaded.onSuccess { list ->
                messages.addAll(list)
                if (list.isNotEmpty()) runCatching { listState.animateScrollToItem(list.size - 1) }
            }
        }
        historyLoaded = true
    }

    // 接收实时消息
    LaunchedEffect(chatType, historyLoaded) {
        app.webSocketClient.events.collect { event ->
            when (event) {
                is ChatEvent.PrivateMessage -> {
                    if (chatType is ChatType.Private) {
                        val msg = event.message
                        val cu = currentUser?.id
                        if ((msg.fromId == chatType.friendId && msg.toId == cu) ||
                            (msg.fromId == cu && msg.toId == chatType.friendId)) {
                            messages.add(msg)
                            if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
                        }
                    }
                }
                is ChatEvent.GroupMessage -> {
                    if (chatType is ChatType.Group) {
                        if (event.message.groupId == chatType.groupId) {
                            messages.add(event.message)
                            if (messages.isNotEmpty()) runCatching { listState.animateScrollToItem(messages.size - 1) }
                        }
                    }
                }
                is ChatEvent.Error -> {
                    sendingError = event.message
                }
                else -> {}
            }
        }
    }

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
                if (!ok) sendingError = "发送图片失败"
            }.onFailure { e -> sendingError = "上传图片：${e.message}" }
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = r.data?.data ?: return@rememberLauncherForActivityResult
        scope.launch {
            sendingError = null
            val token = app.preferenceManager.token.first()
            if (token.isNullOrBlank()) { sendingError = "未登录"; return@launch }
            val file = withContext(Dispatchers.IO) { uri.toLocalCacheFile(context, "aud", ".mp3") }
            if (file == null) { sendingError = "读取音频失败"; return@launch }
            val up = app.apiClient.uploadAudio(token, file, 3)
            up.onSuccess { uf ->
                val ok = when (chatType) {
                    is ChatType.Private -> app.webSocketClient.sendPrivateAudio(chatType.friendId, uf.url, uf.durationSec)
                    is ChatType.Group -> app.webSocketClient.sendGroupAudio(chatType.groupId, uf.url, uf.durationSec)
                }
                if (!ok) sendingError = "发送语音失败"
            }.onFailure { e -> sendingError = "上传语音：${e.message}" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
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
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(messages) { msg ->
                        val isSelf = currentUser?.id == msg.fromId
                        MessageBubble(
                            message = msg,
                            isSelf = isSelf,
                            showSender = (chatType is ChatType.Group) && !isSelf,
                            mediaUrlResolver = { app.apiClient.mediaUrl(it) }
                        )
                    }
                }
            }
        }

        sendingError?.let { err ->
            Text(
                text = err,
                color = ErrorLight,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 6.dp).fillMaxWidth()
            )
        }

        // 工具栏：图片 / 语音
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
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
                modifier = Modifier.weight(1f).height(30.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("🖼 图片", fontSize = 10.sp) }
            Button(
                onClick = {
                    val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "audio/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickAudio.launch(Intent.createChooser(i, "选择语音"))
                },
                modifier = Modifier.weight(1f).height(30.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) { Text("🔊 语音", fontSize = 10.sp) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
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
                            val sent = when (chatType) {
                                is ChatType.Private -> app.webSocketClient.sendPrivateMessage(chatType.friendId, text)
                                is ChatType.Group -> app.webSocketClient.sendGroupMessage(chatType.groupId, text)
                            }
                            if (sent) messageInput = "" else sendingError = "WebSocket 未连接"
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
    mediaUrlResolver: (String) -> String?
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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
                        val url = if (message.image.isNullOrBlank()) null else mediaUrlResolver(message.image)
                        Text(text = "🖼 图片", fontSize = 9.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        url?.let {
                            Text(
                                text = it.substringAfterLast('/').take(40),
                                fontSize = 8.sp,
                                color = OnSurfaceVariant
                            )
                        }
                    }
                    "audio" -> {
                        val payload = runCatching { JSONObject(message.audio ?: "{}") }.getOrNull()
                        val url = payload?.optString("url")?.takeUnless { it.isNullOrBlank() }
                            ?: message.audio?.takeUnless { it.isNullOrBlank() }
                        val dur = payload?.optInt("duration")?.takeIf { it > 0 }
                        Text(text = "🔊 语音", fontSize = 9.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                if (dur != null) append("${dur}s · ")
                                append(url?.substringAfterLast('/')?.take(32) ?: "音频文件")
                            },
                            fontSize = 8.sp, color = OnSurfaceVariant
                        )
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
