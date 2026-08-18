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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.ui.theme.OnSurfaceVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class DpiOption(val label: String, val scale: Float, val desc: String, val preview: String)

// 字体大小档位（参考 Wear OS / 小屏设计标准）
private val dpiOptions = listOf(
    DpiOption("紧凑", 0.85f, "屏幕极小", "Aa"),
    DpiOption("标准", 1.0f, "默认", "Aa"),
    DpiOption("大", 1.15f, "推荐", "Aa"),
    DpiOption("超大", 1.3f, "视力不佳", "Aa")
)

@Composable
fun OnboardingScreen(navController: NavController) {
    val scope = rememberCoroutineScope()
    val app = SanjinChatApp.instance

    var serverAddress by remember { mutableStateOf("your-server.com:8181") }
    var protocol by remember { mutableStateOf("ws") }
    var selectedDpi by remember { mutableStateOf(1.0f) }

    // 预加载已保存的偏好（用户可能曾经设置过但未完成）
    LaunchedEffect(Unit) {
        app.preferenceManager.serverAddress.first().let {
            if (it.isNotBlank()) serverAddress = it
        }
        app.preferenceManager.serverProtocol.first().let {
            if (it.isNotBlank()) protocol = it
        }
        app.preferenceManager.dpiScale.first().let { selectedDpi = it }
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
                text = "首次设置",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "选择字体大小、协议和服务器",
                color = OnSurfaceVariant,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 字体大小（DPI）
            SectionLabel("字体大小")
            Spacer(modifier = Modifier.height(4.dp))
            dpiOptions.forEach { opt ->
                DpiRow(
                    label = opt.label,
                    desc = opt.desc,
                    previewText = opt.preview,
                    selected = selectedDpi == opt.scale,
                    onClick = {
                        selectedDpi = opt.scale
                        // 立即保存，让全局 LocalDensity 即时刷新预览
                        scope.launch { app.preferenceManager.saveDpiScale(opt.scale) }
                    }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 协议
            SectionLabel("服务器协议")
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ProtocolChip(
                    label = "ws://",
                    desc = "明文 80/8181",
                    selected = protocol == "ws",
                    onClick = { protocol = "ws" },
                    modifier = Modifier.weight(1f)
                )
                ProtocolChip(
                    label = "wss://",
                    desc = "加密 443/TLS",
                    selected = protocol == "wss",
                    onClick = { protocol = "wss" },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // 服务器地址
            SectionLabel("服务器地址")
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = serverAddress,
                onValueChange = { serverAddress = it },
                label = { Text("host:port", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "示例：your-server.com:8181",
                color = OnSurfaceVariant,
                fontSize = 9.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    scope.launch {
                        val addr = serverAddress.trim()
                        app.preferenceManager.saveServerAddress(addr)
                        app.preferenceManager.saveServerProtocol(protocol)
                        app.preferenceManager.saveDpiScale(selectedDpi)
                        app.preferenceManager.setOnboarded(true)
                        app.apiClient.serverAddress = addr
                        app.apiClient.serverProtocol = protocol
                        app.webSocketClient.serverAddress = addr
                        app.webSocketClient.serverProtocol = protocol
                        navController.navigate("login") {
                            popUpTo("onboarding") { inclusive = true }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text("完成并前往登录", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun DpiRow(
    label: String,
    desc: String,
    previewText: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = previewText,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = desc,
                color = if (selected) MaterialTheme.colorScheme.onPrimary
                else OnSurfaceVariant,
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun ProtocolChip(
    label: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
            )
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
        Text(
            text = desc,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else OnSurfaceVariant,
            fontSize = 9.sp
        )
    }
}
