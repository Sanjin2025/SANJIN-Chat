package com.sanjinchat.wear.ui.screen

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.call.CallState
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.util.Locale

/**
 * 全屏通话浮层：覆盖在导航之上，任何页面收到来电都会立刻显示。
 *
 * 渲染器：SurfaceViewRenderer（小天才等设备的视频渲染走硬件合成层）。
 */
@Composable
fun CallOverlay() {
    val app = SanjinChatApp.instance
    val callState by app.callManager.state.collectAsState()
    val status by app.callManager.status.collectAsState()
    val muted by app.callManager.muted.collectAsState()
    val remoteVideoReady by app.callManager.remoteVideoReady.collectAsState()
    val localFrames by app.callManager.localFrameCount.collectAsState()
    val remoteFrames by app.callManager.remoteFrameCount.collectAsState()

    val st = callState
    if (st is CallState.Idle) return

    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(st) {
        if (st is CallState.Active) {
            while (true) {
                tick = System.currentTimeMillis()
                delay(500)
            }
        }
    }

    val peerName = when (st) {
        is CallState.Outgoing -> st.peerName
        is CallState.Incoming -> st.peerName
        is CallState.Connecting -> st.peerName
        is CallState.Active -> st.peerName
        else -> ""
    }
    val isVideo = when (st) {
        is CallState.Outgoing -> st.media == "video"
        is CallState.Incoming -> st.media == "video"
        is CallState.Connecting -> st.media == "video"
        is CallState.Active -> st.media == "video"
        else -> false
    }
    val elapsed = if (st is CallState.Active) {
        ((tick - st.connectedAt) / 1000).coerceAtLeast(0)
    } else 0L
    val timeText = if (st is CallState.Active) {
        String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60)
    } else null

    Box(
        // 视频模式不设置任何背景，保证 SurfaceView 能透过窗口显示
        modifier = if (isVideo) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(Color(0xFF141018)),
        contentAlignment = Alignment.Center
    ) {
        if (isVideo) {
            // 远端画面：铺满全屏，默认 z-order（在窗口之下，靠不画背景透出）
            VideoSurface(
                modifier = Modifier.fillMaxSize(),
                isLocal = false
            )
            // 本地预览：右上角小窗，media overlay 叠在远端画面之上
            // 注意：SurfaceView 不能加 clip/圆角，否则会被渲染到离屏层导致画面消失
            VideoSurface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(64.dp, 80.dp),
                isLocal = true
            )

            // 画面诊断提示：没有帧时告诉用户卡在哪一步（放在左上角，避免被底部操作条遮住）
            if (remoteFrames == 0 || localFrames == 0) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                ) {
                    if (remoteFrames == 0) {
                        Text(
                            text = if (!remoteVideoReady) "远端：未收到轨道" else "远端：已收轨道 / 0 帧",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 9.sp
                        )
                    }
                    if (localFrames == 0) {
                        Text(
                            text = "本地：摄像头无画面",
                            color = Color.Yellow.copy(alpha = 0.9f),
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        // 底部信息 + 操作区（Compose 绘制在窗口层，位于视频之上）
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(if (isVideo) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            if (!isVideo) {
                Text("📞", fontSize = 36.sp)
                Spacer(modifier = Modifier.height(6.dp))
            }
            Text(
                text = peerName,
                color = Color.White,
                fontSize = if (isVideo) 14.sp else 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = timeText ?: status.ifBlank { "..." },
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(if (isVideo) 8.dp else 16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (st) {
                    is CallState.Incoming -> {
                        CircleBtn("✕", MaterialTheme.colorScheme.error, 44.dp) { app.callManager.reject() }
                        CircleBtn("✓", Color(0xFF2E7D32), 44.dp) { app.callManager.accept() }
                    }
                    else -> {
                        CircleBtn(
                            if (muted) "🔇" else "🎙️",
                            Color.White.copy(alpha = 0.18f),
                            if (isVideo) 40.dp else 48.dp
                        ) { app.callManager.toggleMute() }
                        if (isVideo) {
                            CircleBtn("🔄", Color.White.copy(alpha = 0.18f), 40.dp) {
                                app.callManager.switchCamera()
                            }
                        }
                        CircleBtn(
                            "✕",
                            MaterialTheme.colorScheme.error,
                            if (isVideo) 40.dp else 48.dp
                        ) { app.callManager.hangup() }
                    }
                }
            }
        }
    }
}

/** WebRTC SurfaceViewRenderer，绑定/解绑本地/远端视频轨 */
@Composable
private fun VideoSurface(
    modifier: Modifier,
    isLocal: Boolean
) {
    val app = SanjinChatApp.instance
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            // z-order 必须在 View attach 之前设置
            if (isLocal) runCatching { setZOrderMediaOverlay(true) }
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            init(app.callManager.eglContext(), null)
            if (isLocal) setMirror(true)
        }
    }
    DisposableEffect(renderer) {
        if (isLocal) app.callManager.attachLocalRenderer(renderer)
        else app.callManager.attachRemoteRenderer(renderer)
        onDispose {
            if (isLocal) app.callManager.attachLocalRenderer(null)
            else app.callManager.attachRemoteRenderer(null)
            runCatching { renderer.release() }
        }
    }
    AndroidView(factory = { renderer }, modifier = modifier)
}

@Composable
private fun CircleBtn(
    text: String,
    bg: Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(size),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White
        )
    ) {
        Text(text, fontSize = if (size.value < 46f) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
    }
}
