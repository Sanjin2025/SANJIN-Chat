package com.sanjinchat.wear.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.call.CallState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * 全屏通话浮层：覆盖在导航之上，任何页面收到来电都会立刻显示。
 */
@Composable
fun CallOverlay() {
    val app = SanjinChatApp.instance
    val callState by app.callManager.state.collectAsState()
    val status by app.callManager.status.collectAsState()
    val muted by app.callManager.muted.collectAsState()

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
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141018))
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(if (isVideo) "🎥" else "📞", fontSize = 40.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = peerName,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeText ?: status.ifBlank { "..." },
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (st) {
                    is CallState.Incoming -> {
                        CircleBtn("✕", MaterialTheme.colorScheme.error) { app.callManager.reject() }
                        CircleBtn("✓", Color(0xFF2E7D32)) { app.callManager.accept() }
                    }
                    else -> {
                        CircleBtn(
                            if (muted) "🔇" else "🎙️",
                            Color.White.copy(alpha = 0.18f)
                        ) { app.callManager.toggleMute() }
                        CircleBtn("✕", MaterialTheme.colorScheme.error) { app.callManager.hangup() }
                    }
                }
            }
        }
    }
}

@Composable
private fun CircleBtn(text: String, bg: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = bg,
            contentColor = Color.White
        )
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
