package com.sanjinchat.wear.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.network.ApiClient
import kotlinx.coroutines.launch

/**
 * 登录后自动检查到新版本时的提示对话框。
 * 提供「下载安装」与「跳过」两个操作，下载时显示进度。
 */
@Composable
fun UpdatePromptDialog(
    update: ApiClient.AppUpdate,
    onDismiss: () -> Unit
) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()

    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "新版本：${update.versionName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                if (update.changelog.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text("更新内容：", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(update.changelog, fontSize = 11.sp)
                }
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    Text("下载中 $progress%", fontSize = 11.sp)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (installed) {
                    Spacer(Modifier.height(8.dp))
                    Text("下载完成，请在系统安装界面完成安装", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    scope.launch {
                        downloading = true
                        progress = 0
                        error = null
                        app.apiClient.downloadApk(update.apkUrl) { pct -> progress = pct }.fold(
                            onSuccess = { apkFile ->
                                app.apiClient.installApk(apkFile)
                                installed = true
                            },
                            onFailure = { e -> error = "下载失败：${e.message ?: "未知错误"}" }
                        )
                        downloading = false
                    }
                },
                enabled = !downloading
            ) {
                Text(if (downloading) "下载中…" else "下载安装")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !downloading) {
                Text("跳过")
            }
        }
    )
}
