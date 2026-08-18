package com.sanjinchat.wear.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.ui.theme.ErrorDeep
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class LoginMode { LOGIN, REGISTER, PAIR }

@Composable
fun LoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val app = SanjinChatApp.instance

    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    // 服务器设置：只从 PreferenceManager 读取，不允许在此页面直接修改（入口在主界面「高级设置」）
    var serverAddress by remember { mutableStateOf("sanjin666.dpdns.org:8181") }
    var protocol by remember { mutableStateOf("ws") }
    var dpiScale by remember { mutableStateOf(1.0f) }
    var rememberLogin by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // 缓存：从本地加载上次的服务器地址、协议、用户名、DPI（登录数据缓存）
    LaunchedEffect(Unit) {
        app.preferenceManager.serverAddress.first().let {
            if (it.isNotBlank()) serverAddress = it
        }
        app.preferenceManager.serverProtocol.first().let {
            if (it.isNotBlank()) protocol = it
        }
        app.preferenceManager.dpiScale.first().let {
            if (it in 0.5f..2.0f) dpiScale = it
        }
        app.preferenceManager.username.first().let {
            if (!it.isNullOrBlank()) username = it
        }
        app.preferenceManager.rememberLogin.first().let { rememberLogin = it }

        // 应用到 ApiClient/WSClient（登录页必须自行拉取最新偏好后才能网络请求）
        app.apiClient.serverAddress = serverAddress
        app.apiClient.serverProtocol = protocol
        app.webSocketClient.serverAddress = serverAddress
        app.webSocketClient.serverProtocol = protocol
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "三金聊天",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Android Wear / 手机通用版",
                color = OnSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 当前连接配置（只读提示），用户到主界面「高级设置」去改
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("服务器", color = OnSurfaceVariant, fontSize = 10.sp)
                        Text(serverAddress, color = MaterialTheme.colorScheme.onSurface, fontSize = 10.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("协议", color = OnSurfaceVariant, fontSize = 10.sp)
                        Text(
                            if (protocol == "wss") "wss://" else "ws://",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = "登录后进入 设置 → 高级设置 修改",
                        color = OnSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 模式切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ModeChip(
                    label = "登录",
                    selected = mode == LoginMode.LOGIN,
                    onClick = { mode = LoginMode.LOGIN; errorMessage = null },
                    modifier = Modifier.weight(1f)
                )
                ModeChip(
                    label = "注册",
                    selected = mode == LoginMode.REGISTER,
                    onClick = { mode = LoginMode.REGISTER; errorMessage = null },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            ModeChip(
                label = "配对码登录",
                selected = mode == LoginMode.PAIR,
                onClick = { mode = LoginMode.PAIR; errorMessage = null },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            when (mode) {
                LoginMode.LOGIN -> LoginForm(
                    username = username,
                    password = password,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it }
                )
                LoginMode.REGISTER -> RegisterForm(
                    username = username,
                    password = password,
                    nickname = nickname,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onNicknameChange = { nickname = it }
                )
                LoginMode.PAIR -> PairForm(
                    pairCode = pairCode,
                    onPairCodeChange = { pairCode = it }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            errorMessage?.let {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ErrorDeep)
                ) {
                    Text(
                        text = it,
                        color = ErrorLight,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            // 记住登录（缓存 token 自动登录）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { rememberLogin = !rememberLogin }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (rememberLogin) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .padding(2.dp)
                ) {
                    Text(
                        text = if (rememberLogin) "✓" else "",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "记住登录（下次自动进入）",
                    color = OnSurfaceVariant,
                    fontSize = 10.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    scope.launch {
                        val addr = serverAddress.trim()
                        app.preferenceManager.saveServerAddress(addr)
                        app.preferenceManager.saveServerProtocol(protocol)
                        app.preferenceManager.setRememberLogin(rememberLogin)
                        app.apiClient.serverAddress = addr
                        app.apiClient.serverProtocol = protocol
                        app.webSocketClient.serverAddress = addr
                        app.webSocketClient.serverProtocol = protocol

                        try {
                            when (mode) {
                                LoginMode.LOGIN -> {
                                    val result = app.apiClient.login(username.trim(), password)
                                    result.fold(
                                        onSuccess = { (token, user) ->
                                            app.preferenceManager.saveLoginInfo(
                                                token, user.id, user.username, user.nickname
                                            )
                                            app.webSocketClient.connect(token)
                                            navController.navigate("main") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onFailure = { e -> errorMessage = e.message }
                                    )
                                }
                                LoginMode.REGISTER -> {
                                    val result = app.apiClient.register(
                                        username.trim(), password, nickname.trim()
                                    )
                                    result.fold(
                                        onSuccess = {
                                            val loginResult = app.apiClient.login(
                                                username.trim(), password
                                            )
                                            loginResult.fold(
                                                onSuccess = { (token, user) ->
                                                    app.preferenceManager.saveLoginInfo(
                                                        token, user.id, user.username, user.nickname
                                                    )
                                                    app.webSocketClient.connect(token)
                                                    navController.navigate("main") {
                                                        popUpTo("login") { inclusive = true }
                                                    }
                                                },
                                                onFailure = { e -> errorMessage = e.message }
                                            )
                                        },
                                        onFailure = { e -> errorMessage = e.message }
                                    )
                                }
                                LoginMode.PAIR -> {
                                    val result = app.apiClient.loginWithPairCode(pairCode.trim())
                                    result.fold(
                                        onSuccess = { (token, user) ->
                                            app.preferenceManager.saveLoginInfo(
                                                token, user.id, user.username, user.nickname
                                            )
                                            app.webSocketClient.connect(token)
                                            navController.navigate("main") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onFailure = { e -> errorMessage = e.message }
                                    )
                                }
                            }
                        } finally {
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isLoading
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isLoading) "处理中..." else when (mode) {
                            LoginMode.LOGIN -> "登录"
                            LoginMode.REGISTER -> "注册并登录"
                            LoginMode.PAIR -> "配对码登录"
                        },
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .height(36.dp),
        colors = if (selected) {
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.primary,
                labelColor = MaterialTheme.colorScheme.onPrimary,
                iconColor = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurface,
                iconColor = MaterialTheme.colorScheme.onSurface
            )
        }
    )
}

@Composable
private fun LoginForm(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(5.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

@Composable
private fun RegisterForm(
    username: String,
    password: String,
    nickname: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onNicknameChange: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("用户名", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = onNicknameChange,
            label = { Text("昵称", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
    }
}

@Composable
private fun PairForm(
    pairCode: String,
    onPairCodeChange: (String) -> Unit
) {
    Column {
        OutlinedTextField(
            value = pairCode,
            onValueChange = { onPairCodeChange(it.uppercase()) },
            label = { Text("配对码", fontSize = 11.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("6位配对码", color = Color.Gray, fontSize = 10.sp) }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "在已登录设备生成配对码后输入",
            color = OnSurfaceVariant,
            fontSize = 10.sp
        )
    }
}
