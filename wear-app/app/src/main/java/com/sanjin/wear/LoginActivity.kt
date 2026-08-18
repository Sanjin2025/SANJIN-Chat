// ==================== 登录页（账密 + 配对码） ====================
package com.sanjin.wear

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sanjin.wear.model.ApiResponse
import com.sanjin.wear.ui.EmptyState
import com.sanjin.wear.ui.LoadingIndicator
import com.sanjin.wear.ui.SanjinWearTheme
import com.sanjin.wear.ui.WearColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录页 Activity
 * - 默认服务器地址 your-server.com:8181
 * - 两种模式：账密登录 / 6 位配对码登录
 * - Jetpack Compose Wear 圆形/方形自适应
 */
class LoginActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)
        NotificationHelper.initChannel(this)

        setContent {
            val scope = rememberCoroutineScope()
            var uiScale by remember { mutableStateOf(PreferencesManager.DEFAULT_SCALE) }
            var firstDone by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    uiScale = prefs.getUiScale()
                    firstDone = prefs.isFirstLaunchDone()
                }
            }
            SanjinWearTheme(uiScale = uiScale) {
                if (!firstDone) {
                    DpiPickerScreen(
                        currentScale = uiScale,
                        onConfirm = { chosen ->
                            scope.launch(Dispatchers.IO) {
                                prefs.setUiScale(chosen)
                                prefs.markFirstLaunchDone()
                            }
                            // 刷新当前 Activity 以应用新的缩放
                            recreate()
                        }
                    )
                } else {
                    LoginScreen()
                }
            }
        }
    }

    // ================================================================
    // DPI / UI 缩放选择引导页（首次进入显示）
    // ================================================================
    @Composable
    fun DpiPickerScreen(currentScale: Float, onConfirm: (Float) -> Unit) {
        var selected by remember { mutableStateOf(currentScale) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(4.dp))
            Text("🔍", fontSize = 30.sp)
            Spacer(Modifier.height(2.dp))
            Text("选择适合的大小",
                style = MaterialTheme.typography.h6,
                color = MaterialTheme.colors.primary
            )
            Spacer(Modifier.height(2.dp))
            Text("根据您的手表屏幕选择缩放级别",
                style = MaterialTheme.typography.caption,
                color = WearColors.TextSecondary
            )
            Spacer(Modifier.height(10.dp))

            val options = listOf(
                0.85f to "紧凑",
                1.0f  to "标准",
                1.15f to "较大",
                1.3f  to "特大"
            )
            options.forEach { (scale, label) ->
                val isSel = selected == scale
                Spacer(Modifier.height(5.dp))
                Button(
                    onClick = { selected = scale },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (isSel) MaterialTheme.colors.primary else WearColors.SurfaceVariant
                    )
                ) {
                    Text(
                        "$label  (${(scale * 100).toInt()}%)",
                        color = if (isSel) Color.White else MaterialTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            // 预览区域：用选中的缩放包裹一小块样例
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WearColors.SurfaceVariant, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                        density = androidx.compose.ui.platform.LocalDensity.current.density * (selected / currentScale.coerceAtLeast(0.01f)),
                        fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale * (selected / currentScale.coerceAtLeast(0.01f))
                    )
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("预览文字",
                            style = MaterialTheme.typography.subtitle1,
                            color = MaterialTheme.colors.primary)
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.material.OutlinedButton(
                            onClick = {},
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                            shape = RoundedCornerShape(20.dp)
                        ) { Text("按钮样例", fontSize = 13.sp) }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onConfirm(selected) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary)
            ) {
                Text("确认并继续",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }

    // ================================================================
    // 登录 UI
    // ================================================================
    @Composable
    fun LoginScreen() {
        val scope = rememberCoroutineScope()

        // ---- 输入状态 ----
        var server by remember { mutableStateOf(PreferencesManager.DEFAULT_SERVER) }
        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var nickname by remember { mutableStateOf("") }
        var pairCode by remember { mutableStateOf("") }

        // ---- 模式 ----
        var tab by remember { mutableStateOf(0) } // 0 = 账密, 1 = 配对码
        var isRegisterMode by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var errorText by remember { mutableStateOf<String?>(null) }

        // 启动时从本地读取上次的服务器地址
        LaunchedEffect(Unit) {
            try {
                server = withContext(Dispatchers.IO) { prefs.getServer() }
            } catch (_: Exception) {}
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .background(MaterialTheme.colors.background)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== 顶部 Logo =====
            Spacer(Modifier.height(6.dp))
            Text("🐾", fontSize = 28.sp)
            Spacer(Modifier.height(2.dp))
            Text(getString(R.string.app_name),
                style = MaterialTheme.typography.h6,
                color = MaterialTheme.colors.primary
            )
            Text("手表版",
                style = MaterialTheme.typography.caption,
                color = WearColors.TextSecondary
            )
            Spacer(Modifier.height(8.dp))

            // ===== Tab 切换 =====
            TabRow(
                selectedTabIndex = tab,
                backgroundColor = Color.Transparent,
                contentColor = MaterialTheme.colors.primary
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }) {
                    Text(getString(R.string.login_account_tab), fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                Tab(selected = tab == 1, onClick = { tab = 1 }) {
                    Text(getString(R.string.login_pair_tab), fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (tab == 0) {
                AccountLoginForm(
                    server = server,
                    username = username,
                    password = password,
                    nickname = nickname,
                    isRegisterMode = isRegisterMode,
                    loading = loading,
                    errorText = errorText,
                    onServerChange = { server = it },
                    onUserChange = { username = it },
                    onPassChange = { password = it },
                    onNickChange = { nickname = it },
                    onToggleMode = { isRegisterMode = !isRegisterMode },
                    onSubmit = {
                        loading = true
                        errorText = null
                        scope.launch {
                            val ok = doLoginOrRegister(server, username, password, nickname, isRegisterMode)
                            loading = false
                            if (ok != null) errorText = ok else gotoMain()
                        }
                    }
                )
            } else {
                PairLoginForm(
                    server = server,
                    pairCode = pairCode,
                    loading = loading,
                    errorText = errorText,
                    onServerChange = { server = it },
                    onCodeChange = { if (it.length <= 6) pairCode = it.uppercase().filter { c -> c.isLetterOrDigit() } },
                    onSubmit = {
                        if (pairCode.length != 6) { errorText = "请输入完整的 6 位配对码"; return@PairLoginForm }
                        loading = true
                        errorText = null
                        scope.launch {
                            val ok = doPairLogin(server, pairCode)
                            loading = false
                            if (ok != null) errorText = ok else gotoMain()
                        }
                    }
                )
            }
        }
    }

    // ================================================================
    // 账密登录/注册表单
    // ================================================================
    @Composable
    fun AccountLoginForm(
        server: String, username: String, password: String, nickname: String,
        isRegisterMode: Boolean, loading: Boolean, errorText: String?,
        onServerChange: (String) -> Unit, onUserChange: (String) -> Unit,
        onPassChange: (String) -> Unit, onNickChange: (String) -> Unit,
        onToggleMode: () -> Unit, onSubmit: () -> Unit
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InputField(server, "服务器地址", true, onServerChange,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            InputField(username, getString(R.string.login_hint_username), true, onUserChange,
                imeAction = ImeAction.Next)
            val passFR = remember { FocusRequester() }
            InputField(password, getString(R.string.login_hint_password), true, onPassChange,
                visualTransform = PasswordVisualTransformation(),
                focusReq = passFR,
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done,
                onImeAction = { if (!isRegisterMode) onSubmit() }
            )
            if (isRegisterMode) {
                InputField(nickname, getString(R.string.login_hint_nickname), false, onNickChange,
                    imeAction = ImeAction.Done, onImeAction = onSubmit)
            }
            if (errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(errorText, color = MaterialTheme.colors.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (loading) {
                LoadingIndicator()
            } else {
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
                ) {
                    Text(
                        if (isRegisterMode) getString(R.string.login_register_btn)
                        else getString(R.string.login_btn),
                        color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onToggleMode, modifier = Modifier.height(44.dp).fillMaxWidth()) {
                    Text(
                        if (isRegisterMode) "已有账号？登录"
                        else getString(R.string.login_register),
                        color = WearColors.TextSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // ================================================================
    // 配对码登录表单
    // ================================================================
    @Composable
    fun PairLoginForm(
        server: String, pairCode: String,
        loading: Boolean, errorText: String?,
        onServerChange: (String) -> Unit, onCodeChange: (String) -> Unit,
        onSubmit: () -> Unit
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InputField(server, "服务器地址", true, onServerChange,
                keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next)
            // 配对码显示框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WearColors.SurfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = pairCode.ifBlank { "＿＿＿＿＿＿" },
                    modifier = Modifier.align(Alignment.Center),
                    fontSize = 22.sp,
                    letterSpacing = 6.sp,
                    color = if (pairCode.isBlank()) WearColors.TextHint else MaterialTheme.colors.primary
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = pairCode,
                onValueChange = onCodeChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(getString(R.string.login_hint_paircode), fontSize = 11.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = Color.White,
                    unfocusedLabelColor = WearColors.TextHint,
                    focusedLabelColor = MaterialTheme.colors.primary
                )
            )
            if (errorText != null) {
                Spacer(Modifier.height(4.dp))
                Text(errorText, color = MaterialTheme.colors.error, fontSize = 12.sp)
            }
            Spacer(Modifier.height(6.dp))
            if (loading) {
                LoadingIndicator()
            } else {
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary)
                ) {
                    Text(getString(R.string.login_pair_btn), color = Color.White, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
    }

    // ================================================================
    // 通用输入框
    // ================================================================
    @Composable
    fun InputField(
        value: String, label: String, single: Boolean = true,
        onValueChange: (String) -> Unit,
        visualTransform: VisualTransformation = VisualTransformation.None,
        focusReq: FocusRequester? = null,
        keyboardType: KeyboardType = KeyboardType.Text,
        imeAction: ImeAction = ImeAction.Done,
        onImeAction: (() -> Unit)? = null
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth().then(
                if (focusReq != null) Modifier.focusRequester(focusReq) else Modifier
            ),
            singleLine = single,
            visualTransformation = visualTransform,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp),
            colors = TextFieldDefaults.outlinedTextFieldColors(
                textColor = Color.White,
                unfocusedLabelColor = WearColors.TextHint,
                focusedLabelColor = MaterialTheme.colors.primary
            )
        )
        Spacer(Modifier.height(6.dp))
    }

    // ================================================================
    // 业务逻辑
    // ================================================================

    /**
     * 登录或注册
     * @return null = 成功，否则错误文字
     */
    private suspend fun doLoginOrRegister(
        server: String,
        username: String,
        password: String,
        nickname: String,
        isRegister: Boolean
    ): String? = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext "请输入用户名"
        if (password.isBlank()) return@withContext "请输入密码"
        if (server.isBlank()) return@withContext "请输入服务器地址"
        try {
            prefs.setServer(server)
            val resp = if (isRegister) {
                ApiClient.register(server, username, password, nickname.ifBlank { null })
            } else {
                ApiClient.login(server, username, password)
            }
            if (!resp.ok || resp.token.isNullOrBlank() || resp.user == null) {
                return@withContext resp.error
                    ?: (if (isRegister) "注册失败" else getString(R.string.error_auth))
            }
            // 保存登录信息
            prefs.saveLogin(resp.token, resp.user)
            // 初始化 WebSocket
            WebSocketManager.instance.connect(WearApp.instance.appScope, server, resp.token)
            return@withContext null
        } catch (e: Exception) {
            return@withContext (e.message ?: getString(R.string.error_network))
        }
    }

    /**
     * 配对码登录
     */
    private suspend fun doPairLogin(server: String, code: String): String? = withContext(Dispatchers.IO) {
        if (server.isBlank()) return@withContext "请输入服务器地址"
        try {
            prefs.setServer(server)
            val resp = ApiClient.pairLogin(server, code)
            if (!resp.ok || resp.token.isNullOrBlank() || resp.user == null) {
                return@withContext resp.error ?: "配对码无效或已过期"
            }
            prefs.saveLogin(resp.token, resp.user)
            WebSocketManager.instance.connect(WearApp.instance.appScope, server, resp.token)
            return@withContext null
        } catch (e: Exception) {
            return@withContext (e.message ?: getString(R.string.error_network))
        }
    }

    /** 登录成功：跳转到主界面 */
    private fun gotoMain() {
        startActivity(Intent(this, ChatListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
