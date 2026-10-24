package com.sanjinchat.wear.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.network.ApiClient
import com.sanjinchat.wear.network.ConnectionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 启动页 / 会话恢复页（cookie 式自动登录）
 *
 * 作用：把"本地存了 token 就直接进主界面"改成"用 token 真的走一遍登录校验"，
 * 从而彻底消除假登录（界面显示已登录、实际会话已失效、后台一直卡在连接中）。
 *
 * 流程：
 * 1. 还没配置显示密度 → dpi_setup 引导页
 * 2. 没有 token 或用户没勾选"记住登录" → 登录页
 * 3. 有 token → 用它向服务端校验会话（getMyProfile）
 *    - 校验通过 → 刷新本地登录信息（cookie 续期）→ 建立 WebSocket → 检查更新 → 进入主界面
 *    - 校验失败（token 过期/被拒）→ 清除本地登录信息 → 带提示回到登录页
 */
@Composable
fun BootScreen(navController: NavController) {
    val app = SanjinChatApp.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("正在恢复登录…") }

    // 自动更新检查相关状态
    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<ApiClient.AppUpdate?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val currentVersionCode = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) it.longVersionCode.toInt()
                else @Suppress("DEPRECATION") it.versionCode
            }
        }.getOrDefault(0)
    }

    LaunchedEffect(Unit) {
        // 1. 引导页：首次启动还没配置显示密度
        if (!app.preferenceManager.dpiConfigured.first()) {
            navController.navigate("dpi_setup") {
                popUpTo("boot") { inclusive = true }
            }
            return@LaunchedEffect
        }

        val token = app.preferenceManager.token.first()
        val rememberLogin = app.preferenceManager.rememberLogin.first()
        // 2. 没有可用的会话 cookie → 登录页
        if (token.isNullOrBlank() || !rememberLogin) {
            navController.navigate("login") {
                popUpTo("boot") { inclusive = true }
            }
            return@LaunchedEffect
        }

        // 3. cookie 自动登录：用保存的 token 真实校验一次会话
        status = "正在校验登录状态…"
        val result = runCatching { app.apiClient.getMyProfile(token) }.getOrNull()
        val user = result?.getOrNull()
        if (user == null) {
            // 校验失败：清掉失效的 cookie 并回登录页，绝不进入"假登录"的主界面
            val reason = result?.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "会话已失效"
            app.preferenceManager.clearLoginInfo()
            runCatching { app.webSocketClient.disconnect() }
            app.pendingLoginNotice = "登录已过期，请重新登录（$reason）"
            navController.navigate("login") {
                popUpTo("boot") { inclusive = true }
            }
            return@LaunchedEffect
        }

        // 4. 校验通过：刷新本地登录信息（相当于 cookie 续期），并让 WS 连上前就能判断消息归属
        app.preferenceManager.saveLoginInfo(token, user.id, user.username, user.nickname)
        app.webSocketClient.setCurrentUser(user)

        // 5. 建立实时连接，等握手成功再进主界面
        status = "正在连接服务器…"
        app.webSocketClient.connect(token)
        val state = withTimeoutOrNull(12_000) {
            app.webSocketClient.connectionState.first {
                it is ConnectionState.Connected || it is ConnectionState.Error
            }
        }
        if (state !is ConnectionState.Connected) {
            status = "实时连接暂不可用，正在重试…"
            delay(600)
        }

        // 6. 登录后自动检查更新（失败不阻塞进入主界面）
        status = "正在检查更新…"
        val channel = app.preferenceManager.updateChannel.first()
        val updateResult = runCatching { app.apiClient.checkUpdate(channel, currentVersionCode) }.getOrNull()
        val update = updateResult?.getOrNull()
        if (update != null && update.hasUpdate) {
            updateInfo = update
            showUpdateDialog = true
            // 等待用户处理更新对话框（跳过或安装完成后手动进主界面）
            return@LaunchedEffect
        }

        navController.navigate("main") {
            popUpTo("boot") { inclusive = true }
        }
    }

    val breathing = rememberInfiniteTransition(label = "bootBreath")
    val logoScale by breathing.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bootLogoScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(WindowInsets.statusBars.asPaddingValues())
            .padding(WindowInsets.navigationBars.asPaddingValues())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "三金",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = status,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 自动更新提示对话框
        if (showUpdateDialog && updateInfo != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!downloading) {
                        showUpdateDialog = false
                        navController.navigate("main") { popUpTo("boot") { inclusive = true } }
                    }
                },
                title = { Text("发现新版本", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text(
                            "新版本：${updateInfo!!.versionName}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (updateInfo!!.changelog.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("更新内容：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(updateInfo!!.changelog, fontSize = 11.sp)
                        }
                        if (downloading) {
                            Spacer(Modifier.height(8.dp))
                            Text("下载中 $downloadProgress%", fontSize = 11.sp)
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        downloadError?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    if (downloading) {
                        Button(onClick = {}, enabled = false) { Text("下载中…") }
                    } else {
                        Button(onClick = {
                            scope.launch {
                                downloading = true
                                downloadProgress = 0
                                downloadError = null
                                app.apiClient.downloadApk(updateInfo!!.apkUrl) { pct -> downloadProgress = pct }.fold(
                                    onSuccess = { apkFile -> app.apiClient.installApk(apkFile) },
                                    onFailure = { e -> downloadError = "下载失败：${e.message ?: "未知错误"}" }
                                )
                                downloading = false
                            }
                        }) { Text("下载安装") }
                    }
                },
                dismissButton = {
                    if (!downloading) {
                        OutlinedButton(onClick = {
                            showUpdateDialog = false
                            navController.navigate("main") { popUpTo("boot") { inclusive = true } }
                        }) { Text("跳过") }
                    }
                }
            )
        }
    }
}
