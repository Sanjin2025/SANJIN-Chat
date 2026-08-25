package com.sanjinchat.wear.ui.screen

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.ui.theme.ErrorDeep
import com.sanjinchat.wear.ui.theme.ErrorLight
import com.sanjinchat.wear.ui.theme.ExpressiveMotion
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class LoginMode { LOGIN, REGISTER, PAIR, QR, OTP }

@Composable
fun LoginScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val app = SanjinChatApp.instance

    var mode by remember { mutableStateOf(LoginMode.LOGIN) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var pairCode by remember { mutableStateOf("") }
    // 验证码登录字段
    var otpUsername by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }
    var otpInfo by remember { mutableStateOf<ApiClient.OtpInfo?>(null) }
    var otpCountdown by remember { mutableStateOf(0) }
    var otpGenLoading by remember { mutableStateOf(false) }
    var otpError by remember { mutableStateOf<String?>(null) }
    // 服务器设置：第一次进入强制使用固定地址 sanjin666.dpdns.org:8181 + ws
    // 不允许在登录页修改，登录后可在主界面「高级设置」修改
    val serverAddress = "sanjin666.dpdns.org:8181"
    val protocol = "ws"
    var rememberLogin by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // 缓存：从本地加载用户名、记住登录（不读取服务器地址/协议，强制使用默认值）
    LaunchedEffect(Unit) {
        val savedUser = app.preferenceManager.username.first()
        if (!savedUser.isNullOrBlank()) {
            username = savedUser
            otpUsername = savedUser
        }
        app.preferenceManager.rememberLogin.first().let { rememberLogin = it }

        // 应用固定服务器地址/协议到 ApiClient/WSClient
        app.apiClient.serverAddress = serverAddress
        app.apiClient.serverProtocol = protocol
        app.webSocketClient.serverAddress = serverAddress
        app.webSocketClient.serverProtocol = protocol
    }

    // OTP 倒计时（如果生成了验证码）
    LaunchedEffect(otpInfo) {
        val info = otpInfo
        otpCountdown = if (info == null) 0 else info.expiresIn.coerceAtLeast(0)
        while (otpCountdown > 0) {
            kotlinx.coroutines.delay(1000L)
            otpCountdown -= 1
        }
        if (info != null && otpCountdown <= 0) otpInfo = null
    }

    // 圆屏检测：优先用户手动设置，否则自动检测系统
    val screenShape by app.preferenceManager.screenShape.collectAsState(initial = "auto")
    val isRound = when (screenShape) {
        "round" -> true
        "square" -> false
        else -> {
            val config = LocalConfiguration.current
            (config.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK) ==
                Configuration.SCREENLAYOUT_ROUND_YES
        }
    }
    // 圆屏需要更大水平 padding，避免内容被边缘裁剪
    val hPadding = if (isRound) 16.dp else 8.dp
    val scrollState = rememberScrollState()

    // 登录按钮按压交互（Expressive press scale）
    val loginBtnInteraction = remember { MutableInteractionSource() }
    val loginBtnPressed by loginBtnInteraction.collectIsPressedAsState()
    val loginBtnScale by animateFloatAsState(
        targetValue = if (loginBtnPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh),
        label = "loginBtnScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = hPadding, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "三金聊天",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = if (isRound) 15.sp else 18.sp
            )
            Text(
                text = "Android Wear / 手机通用版",
                color = OnSurfaceVariant,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            // 模式切换（紧凑两列 × 三行：登录/注册 / 配对码/扫码 / 验证码）
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeChip(
                        label = "登录",
                        selected = mode == LoginMode.LOGIN,
                        onClick = { mode = LoginMode.LOGIN; errorMessage = null; otpError = null },
                        modifier = Modifier.weight(1f)
                    )
                    ModeChip(
                        label = "注册",
                        selected = mode == LoginMode.REGISTER,
                        onClick = { mode = LoginMode.REGISTER; errorMessage = null; otpError = null },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    ModeChip(
                        label = "配对码",
                        selected = mode == LoginMode.PAIR,
                        onClick = { mode = LoginMode.PAIR; errorMessage = null; otpError = null },
                        modifier = Modifier.weight(1f)
                    )
                    ModeChip(
                        label = "扫码",
                        selected = mode == LoginMode.QR,
                        onClick = { mode = LoginMode.QR; errorMessage = null; otpError = null },
                        modifier = Modifier.weight(1f)
                    )
                }
                ModeChip(
                    label = "验证码登录",
                    selected = mode == LoginMode.OTP,
                    onClick = { mode = LoginMode.OTP; errorMessage = null; otpError = null },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // 登录模式切换：Expressive 形变（横向滑入 + 缩放 + 淡入淡出）
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (slideInHorizontally(animationSpec = tween(350, easing = ExpressiveMotion.EmphasizedEasing)) { it / 3 } +
                        fadeIn(tween(300, easing = ExpressiveMotion.EmphasizedDecelerate)) +
                        scaleIn(
                            initialScale = 0.92f,
                            animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
                        )) togetherWith
                    (slideOutHorizontally(animationSpec = tween(220, easing = ExpressiveMotion.EmphasizedAccelerate)) { -it / 3 } +
                        fadeOut(tween(180)) +
                        scaleOut(
                            targetScale = 0.96f,
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
                        ))
                },
                label = "loginMode",
                contentAlignment = Alignment.Center
            ) { m ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                ) {
                when (m) {
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
                    LoginMode.QR -> QrForm(
                        onConsumeSuccess = { token, user ->
                            scope.launch {
                                app.preferenceManager.saveServerAddress(serverAddress.trim())
                                app.preferenceManager.saveServerProtocol(protocol)
                                app.preferenceManager.saveLoginInfo(
                                    token, user.id, user.username, user.nickname
                                )
                                app.webSocketClient.connect(token)
                                navController.navigate("main") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        },
                        onError = { msg -> errorMessage = msg }
                    )
                    LoginMode.OTP -> OtpForm(
                        otpUsername = otpUsername,
                        otpCode = otpCode,
                        otpInfo = otpInfo,
                        otpCountdown = otpCountdown,
                        genLoading = otpGenLoading,
                        error = otpError,
                        onUsernameChange = { otpUsername = it; otpInfo = null; otpError = null },
                        onCodeChange = { otpCode = it.uppercase().filter { c -> c.isDigit() }.take(6) },
                        onGenerate = {
                            scope.launch {
                                if (otpUsername.isBlank()) {
                                    otpError = "请输入用户名"
                                    return@launch
                                }
                                otpGenLoading = true; otpError = null
                                app.apiClient.generateOtp(otpUsername).fold(
                                    onSuccess = { info ->
                                        otpInfo = info
                                        otpCode = info.code
                                    },
                                    onFailure = { e -> otpError = e.message ?: "生成验证码失败" }
                                )
                                otpGenLoading = false
                            }
                        }
                    )
                }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 错误提示：Expressive 滑入 + 形变
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = ExpressiveMotion.EnterSlideUp,
                exit = ExpressiveMotion.ExitSlideDown
            ) {
                errorMessage?.let {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)),
                        colors = CardDefaults.cardColors(containerColor = ErrorDeep)
                    ) {
                        Text(
                            text = it,
                            color = ErrorLight,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(100))
            ) {
                Spacer(modifier = Modifier.height(5.dp))
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
            Spacer(modifier = Modifier.height(4.dp))
            // 圆屏提示：向下滑动查看登录按钮
            Text(
                text = "↓ 向下滑动",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 1.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

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
                                            runCatching { app.webSocketClient.connect(token) }
                                                .onFailure { e -> errorMessage = "WebSocket：${e.message}" }
                                            navController.navigate("main") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onFailure = { e -> errorMessage = e.message ?: "登录失败" }
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
                                                    runCatching { app.webSocketClient.connect(token) }
                                                        .onFailure { e -> errorMessage = "WebSocket：${e.message}" }
                                                    navController.navigate("main") {
                                                        popUpTo("login") { inclusive = true }
                                                    }
                                                },
                                                onFailure = { e -> errorMessage = e.message ?: "登录失败" }
                                            )
                                        },
                                        onFailure = { e -> errorMessage = e.message ?: "注册失败" }
                                    )
                                }
                                LoginMode.PAIR -> {
                                    val code = pairCode.trim()
                                    if (code.length != 6) {
                                        errorMessage = "请输入 6 位配对码"
                                        return@launch
                                    }
                                    val result = app.apiClient.loginWithPairCode(code)
                                    result.fold(
                                        onSuccess = { (token, user) ->
                                            app.preferenceManager.saveLoginInfo(
                                                token, user.id, user.username, user.nickname
                                            )
                                            runCatching { app.webSocketClient.connect(token) }
                                                .onFailure { e -> errorMessage = "WebSocket：${e.message}" }
                                            navController.navigate("main") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onFailure = { e -> errorMessage = e.message ?: "配对码登录失败" }
                                    )
                                }
                                LoginMode.QR -> {
                                    // 扫码模式由 QrForm 自动消费 token，按钮不应该触发
                                }
                                LoginMode.OTP -> {
                                    if (otpUsername.isBlank() || otpCode.length != 6) {
                                        errorMessage = "请输入用户名和 6 位验证码"
                                        return@launch
                                    }
                                    val result = app.apiClient.loginWithOtp(otpUsername, otpCode)
                                    result.fold(
                                        onSuccess = { (token, user) ->
                                            app.preferenceManager.saveLoginInfo(
                                                token, user.id, user.username, user.nickname
                                            )
                                            runCatching { app.webSocketClient.connect(token) }
                                                .onFailure { e -> errorMessage = "WebSocket：${e.message}" }
                                            navController.navigate("main") {
                                                popUpTo("login") { inclusive = true }
                                            }
                                        },
                                        onFailure = { e -> errorMessage = e.message ?: "验证码登录失败" }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            // 任何未捕获异常都转为错误提示，避免应用闪退
                            errorMessage = "操作失败：${e.message ?: "未知错误"}"
                        } finally {
                            isLoading = false
                        }
                    }
                },
                interactionSource = loginBtnInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = loginBtnScale
                        scaleY = loginBtnScale
                    },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                enabled = !isLoading && mode != LoginMode.QR &&
                    when (mode) {
                        LoginMode.LOGIN -> username.isNotBlank() && password.isNotBlank()
                        LoginMode.REGISTER -> username.isNotBlank() && password.isNotBlank() && nickname.isNotBlank()
                        LoginMode.PAIR -> pairCode.trim().length == 6
                        LoginMode.QR -> false
                        LoginMode.OTP -> otpUsername.isNotBlank() && otpCode.length == 6
                    }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = isLoading,
                        enter = ExpressiveMotion.EnterExpand,
                        exit = ExpressiveMotion.ExitShrink
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp).padding(end = 6.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        text = if (isLoading) "处理中..." else when (mode) {
                            LoginMode.LOGIN -> "登录"
                            LoginMode.REGISTER -> "注册并登录"
                            LoginMode.PAIR -> "配对码登录"
                            LoginMode.QR -> "等待扫码确认"
                            LoginMode.OTP -> "验证码登录"
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.animateContentSize(animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                    )
                }
            }
            // 圆屏底部留白：确保按钮在圆屏下完全可见
            Spacer(modifier = Modifier.height(if (isRound) 20.dp else 8.dp))
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
        label = {
            Text(
                label,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
            )
        },
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .height(30.dp)
            .padding(horizontal = 2.dp, vertical = 2.dp),
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
            label = { Text("配对码", fontSize = 10.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
            placeholder = { Text("6位配对码", color = Color.Gray, fontSize = 10.sp) }
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "在已登录设备生成配对码后输入",
            color = OnSurfaceVariant,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun OtpForm(
    otpUsername: String,
    otpCode: String,
    otpInfo: ApiClient.OtpInfo?,
    otpCountdown: Int,
    genLoading: Boolean,
    error: String?,
    onUsernameChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onGenerate: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = otpUsername,
            onValueChange = onUsernameChange,
            label = { Text("用户名", fontSize = 10.sp) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = otpCode,
                onValueChange = onCodeChange,
                label = { Text("6位验证码", fontSize = 10.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = TextUnit(2f, TextUnitType.Sp)
                ),
                placeholder = { Text("验证码", color = Color.Gray, fontSize = 10.sp) }
            )
            Button(
                onClick = onGenerate,
                enabled = !genLoading && otpUsername.isNotBlank() && otpCountdown <= 0,
                modifier = Modifier.height(42.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                if (genLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        if (otpCountdown > 0) "${otpCountdown}s"
                        else if (otpInfo == null) "获取"
                        else "${otpCountdown}s",
                        fontSize = 10.sp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = when {
                otpInfo != null && otpCountdown > 0 ->
                    "验证码：${otpInfo.code} · 有效期 ${otpCountdown}s（免密登录凭据）"
                else -> "输入用户名后点「获取」，服务器会生成一个 5 分钟有效的 6 位一次性验证码"
            },
            color = OnSurfaceVariant,
            fontSize = 9.sp
        )
        error?.let {
            Spacer(modifier = Modifier.height(3.dp))
            Text(it, color = ErrorLight, fontSize = 9.sp)
        }
    }
}

/**
 * 扫码登录：
 * 进入时调 /api/qrcode/generate 拿到二维码图片（DataURL），展示给用户；
 * 用已登录设备（如手机/PC）扫码确认后，本端轮询 /api/qrcode/status 拿到
 * status=confirmed 时服务端已直接返回 {token, user}，直接用于登录。
 */
@Composable
private fun QrForm(
    onConsumeSuccess: (String, com.sanjinchat.wear.model.User) -> Unit,
    onError: (String) -> Unit
) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()

    var qrDataUrl by remember { mutableStateOf<String?>(null) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var status: ApiClient.QrPoll by remember { mutableStateOf<ApiClient.QrPoll>(ApiClient.QrPoll.Pending) }
    var loading by remember { mutableStateOf(true) }
    var tip by remember { mutableStateOf<String?>(null) }
    var consumed by remember { mutableStateOf(false) }

    // 生成二维码
    fun regenerate() {
        scope.launch {
            loading = true; tip = null; consumed = false
            val r = app.apiClient.generateQrCode()
            r.onSuccess {
                sessionId = it.qrSessionId
                qrDataUrl = it.qrDataUrl
                status = ApiClient.QrPoll.Pending
            }.onFailure { e -> tip = "生成二维码失败：${e.message}" }
            loading = false
        }
    }
    LaunchedEffect(Unit) { regenerate() }

    // 轮询状态：sessionId 不为空且仍 Pending 时每 2 秒查一次
    LaunchedEffect(sessionId, status, consumed) {
        val sid = sessionId ?: return@LaunchedEffect
        if (status !is ApiClient.QrPoll.Pending) return@LaunchedEffect
        if (consumed) return@LaunchedEffect
        while (status is ApiClient.QrPoll.Pending && !consumed) {
            kotlinx.coroutines.delay(2000L)
            val r = app.apiClient.getQrStatus(sid)
            r.onSuccess { poll ->
                status = poll
                when (poll) {
                    is ApiClient.QrPoll.Confirmed -> {
                        if (!consumed) {
                            consumed = true
                            onConsumeSuccess(poll.token, poll.user)
                        }
                    }
                    is ApiClient.QrPoll.Expired -> {
                        tip = "二维码已过期，请刷新"
                    }
                    is ApiClient.QrPoll.Pending -> Unit
                }
            }.onFailure { e -> tip = "查询状态失败：${e.message}" }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (loading) {
            Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
        } else if (qrDataUrl != null) {
            val bitmap = remember(qrDataUrl) {
                runCatching {
                    val b64 = qrDataUrl!!.substringAfter("base64,", qrDataUrl!!)
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
            if (bitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "登录二维码",
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .padding(3.dp)
                    )
                }
            } else {
                Text("二维码解析失败", color = ErrorLight, fontSize = 10.sp)
            }
            Spacer(modifier = Modifier.height(3.dp))
            val s = status
            val tipText = when (s) {
                is ApiClient.QrPoll.Confirmed -> "已确认，登录中..."
                is ApiClient.QrPoll.Expired -> "二维码已过期"
                is ApiClient.QrPoll.Pending -> "请使用已登录设备扫码确认"
                else -> "请使用已登录设备扫码确认"
            }
            Text(
                text = tipText,
                color = if (s is ApiClient.QrPoll.Expired) ErrorLight else OnSurfaceVariant,
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
        tip?.let {
            Spacer(modifier = Modifier.height(3.dp))
            Text(it, color = ErrorLight, fontSize = 9.sp)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Button(
            onClick = { regenerate() },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) { Text("刷新二维码", fontSize = 10.sp) }
    }
}
