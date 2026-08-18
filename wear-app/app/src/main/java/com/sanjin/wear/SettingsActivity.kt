// ==================== 设置页 ====================
package com.sanjin.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sanjin.wear.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页
 * - 显示当前用户信息
 * - 修改服务器地址
 * - 关于（版本号）
 * - 退出登录
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            var uiScale by remember { mutableStateOf(PreferencesManager.DEFAULT_SCALE) }
            LaunchedEffect(Unit) {
                uiScale = withContext(Dispatchers.IO) { prefs.getUiScale() }
            }
            SanjinWearTheme(uiScale = uiScale) {
                SettingsScreen()
            }
        }
    }

    @Composable
    fun SettingsScreen() {
        val vm: SettingsViewModel by viewModels()
        val ctx = LocalContext.current
        var me by remember { mutableStateOf<com.sanjin.wear.model.User?>(null) }
        var server by remember { mutableStateOf(PreferencesManager.DEFAULT_SERVER) }
        val wsState by WebSocketManager.instance.state.collectAsState()
        var showConfirmDialog by remember { mutableStateOf(false) }
        var toastMsg by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                me = prefs.getUser()
                server = prefs.getServer()
            }
        }

        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
            // 顶部栏
            TopBar(title = getString(R.string.settings_title), left = {
                IconButton(onClick = { finish() }) { Text("⬅️", fontSize = 18.sp) }
            })
            com.sanjin.wear.ui.Divider()

            LazyColumn {
                // 个人信息卡
                item {
                    WearCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(me?.displayEmoji ?: "👤", size = 48,
                                showOnline = true, online = wsState == WsState.CONNECTED)
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(me?.displayName ?: "未登录",
                                    style = MaterialTheme.typography.subtitle1)
                                Text("@${me?.username ?: "-"}",
                                    color = WearColors.TextSecondary,
                                    style = MaterialTheme.typography.caption)
                                Text("ID: ${me?.id ?: "-"}",
                                    color = WearColors.TextHint,
                                    style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                }
                // 连接状态
                item {
                    WearCard {
                        RowItem("🔌", "连接状态: ${
                            when (wsState) {
                                WsState.CONNECTED -> "已连接"
                                WsState.CONNECTING -> "连接中"
                                WsState.RECONNECTING -> "重连中"
                                else -> "未连接"
                            }
                        }")
                    }
                }
                // 服务器地址
                item {
                    WearCard {
                        Text(getString(R.string.settings_server),
                            style = MaterialTheme.typography.subtitle1,
                            color = WearColors.TextSecondary,
                            fontSize = 12.sp)
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            singleLine = true,
                            colors = TextFieldDefaults.outlinedTextFieldColors(
                                textColor = Color.White,
                                unfocusedLabelColor = WearColors.TextHint
                            )
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = {
                                    vm.viewModelScope.launch {
                                        withContext(Dispatchers.IO) { prefs.setServer(server) }
                                        toastMsg = getString(R.string.settings_saved)
                                        // 重连
                                        val s = server
                                        val t = runCatching { prefs.getToken() }.getOrDefault("")
                                        if (t.isNotBlank()) {
                                            WebSocketManager.instance.disconnect()
                                            WebSocketManager.instance.connect(WearApp.instance.appScope, s, t)
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = MaterialTheme.colors.primary
                                )
                            ) {
                                Text(getString(R.string.settings_save),
                                    color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
                // 关于
                item {
                    WearCard {
                        val pkgInfo = runCatching {
                            packageManager.getPackageInfo(packageName, 0)
                        }.getOrNull()
                        val versionName = pkgInfo?.versionName ?: "1.0.0"
                        val versionCode = pkgInfo?.versionCode?.toLong() ?: 1L
                        RowItem("ℹ️", getString(R.string.settings_about))
                        RowItem("📦", getString(R.string.settings_version, "$versionName ($versionCode)"))
                        RowItem("💬", getString(R.string.app_name))
                    }
                }
                // 退出登录
                item {
                    WearCard {
                        Button(
                            onClick = { showConfirmDialog = true },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = MaterialTheme.colors.error
                            )
                        ) {
                            Text(getString(R.string.settings_logout),
                                color = Color.White,
                                fontSize = 14.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }

        // 确认退出弹窗
        if (showConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showConfirmDialog = false },
                backgroundColor = MaterialTheme.colors.surface,
                title = { Text(getString(R.string.settings_logout)) },
                text = { Text(getString(R.string.settings_confirm_logout)) },
                confirmButton = {
                    TextButton(onClick = {
                        showConfirmDialog = false
                        vm.viewModelScope.launch {
                            withContext(Dispatchers.IO) { prefs.clearAll() }
                            WebSocketManager.instance.logout()
                            val intent = Intent(ctx, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                            ctx.startActivity(intent)
                            finish()
                        }
                    }) {
                        Text(getString(R.string.confirm), color = MaterialTheme.colors.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showConfirmDialog = false }) {
                        Text(getString(R.string.cancel), color = WearColors.TextSecondary)
                    }
                }
            )
        }

        // Toast
        toastMsg?.let { msg ->
            LaunchedEffect(msg) {
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1500) }
                toastMsg = null
            }
        }
    }
}

/** LazyColumn 别名 */
@Composable
private fun LazyColumn(
    modifier: Modifier = Modifier,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content
    )
}

/** 单行列表项 */
@Composable
private fun RowItem(icon: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.body1)
    }
}

class SettingsViewModel : ViewModel()
