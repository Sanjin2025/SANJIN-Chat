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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.sanjinchat.wear.SanjinChatApp
import com.sanjinchat.wear.ui.theme.ExpressiveMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class DpiOption(val scale: Float, val label: String, val desc: String)

private val DPI_OPTIONS = listOf(
    DpiOption(0.75f, "特小", "信息密度最高"),
    DpiOption(0.85f, "紧凑", "适合小屏手表"),
    DpiOption(0.95f, "偏小", "略小于标准"),
    DpiOption(1.0f, "标准", "默认大小"),
    DpiOption(1.1f, "偏大", "略大于标准"),
    DpiOption(1.2f, "大", "更易阅读"),
    DpiOption(1.3f, "超大", "最大字号"),
)

/**
 * 检测当前是否为圆屏：优先用户手动设置，否则自动检测。
 */
@Composable
private fun rememberIsRoundScreen(app: SanjinChatApp): Boolean {
    val shape by app.preferenceManager.screenShape.collectAsState(initial = "auto")
    return when (shape) {
        "round" -> true
        "square" -> false
        else -> {
            val config = LocalConfiguration.current
            (config.screenLayout and Configuration.SCREENLAYOUT_ROUND_MASK) ==
                Configuration.SCREENLAYOUT_ROUND_YES
        }
    }
}

@Composable
fun DpiSetupScreen(navController: NavController) {
    val app = SanjinChatApp.instance
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(0) } // 0=服务器地址, 1=DPI
    var serverAddr by remember { mutableStateOf("sanjin666.dpdns.org:8181") }
    var selected by remember { mutableFloatStateOf(1.0f) }
    var addrError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        serverAddr = app.preferenceManager.serverAddress.first()
    }

    val isRound = rememberIsRoundScreen(app)
    val hPadding = if (isRound) 18.dp else 10.dp
    val scrollState = rememberScrollState()

    val statusPad = WindowInsets.statusBars.asPaddingValues()
    val navPad = WindowInsets.navigationBars.asPaddingValues()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = statusPad.calculateTopPadding())
            .padding(bottom = navPad.calculateBottomPadding())
            .verticalScroll(scrollState)
            .padding(horizontal = hPadding, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 步骤指示器
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StepDot(active = step >= 0, label = "地址")
            StepBar()
            StepDot(active = step >= 1, label = "密度")
        }
        Spacer(Modifier.height(8.dp))

        // 按钮按压交互（Expressive press scale）
        val nextBtnInteraction = remember { MutableInteractionSource() }
        val nextBtnPressed by nextBtnInteraction.collectIsPressedAsState()
        val nextBtnScale by animateFloatAsState(
            targetValue = if (nextBtnPressed) 0.95f else 1f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh),
            label = "nextBtnScale"
        )
        val finishBtnInteraction = remember { MutableInteractionSource() }
        val finishBtnPressed by finishBtnInteraction.collectIsPressedAsState()
        val finishBtnScale by animateFloatAsState(
            targetValue = if (finishBtnPressed) 0.95f else 1f,
            animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh),
            label = "finishBtnScale"
        )

        // 步骤切换：更激进的 Expressive 形变（1/2 屏宽横向滑入 + 更夸张缩放 + 更长 Emphasized 缓动）
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                (slideInHorizontally(animationSpec = tween(450, easing = ExpressiveMotion.EmphasizedEasing)) { it / 2 } +
                    fadeIn(tween(380, easing = ExpressiveMotion.EmphasizedDecelerate)) +
                    scaleIn(
                        initialScale = 0.88f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
                    )) togetherWith
                (slideOutHorizontally(animationSpec = tween(260, easing = ExpressiveMotion.EmphasizedAccelerate)) { -it / 2 } +
                    fadeOut(tween(200)) +
                    scaleOut(
                        targetScale = 0.92f,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
                    ))
            },
            label = "setupStep",
            contentAlignment = Alignment.TopCenter
        ) { currentStep ->
            if (currentStep == 0) {
            // ===== 步骤1：服务器地址 =====
            Column(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
            Text(
                "服务器地址",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "连接到 SanjinChat 服务端",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverAddr,
                onValueChange = { serverAddr = it; addrError = null },
                label = { Text("地址", fontSize = 9.sp) },
                placeholder = { Text("host:port", fontSize = 9.sp) },
                singleLine = true,
                isError = addrError != null,
                supportingText = {
                    addrError?.let { msg ->
                        Text(msg, fontSize = 8.sp, color = MaterialTheme.colorScheme.error)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "默认连接官方服务器，也可填写自建地址",
                fontSize = 7.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (isRound) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "检测到圆屏，内容已自动适配",
                    fontSize = 7.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    val addr = serverAddr.trim()
                    if (addr.isEmpty()) { addrError = "请输入服务器地址"; return@Button }
                    addrError = null
                    scope.launch {
                        app.preferenceManager.saveServerAddress(addr)
                        app.apiClient.serverAddress = addr
                        app.webSocketClient.serverAddress = addr
                        step = 1
                    }
                },
                interactionSource = nextBtnInteraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .graphicsLayer {
                        scaleX = nextBtnScale
                        scaleY = nextBtnScale
                    },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("下一步", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(if (isRound) 20.dp else 8.dp))
            }
        } else {
            // ===== 步骤2：DPI 选择 =====
            Column(modifier = Modifier.fillMaxWidth().animateContentSize(animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
            Text(
                "选择显示密度",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "可在设置中随时更改",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))

            // DPI 选项列表（普通 Column，不用 LazyColumn，支持外层 verticalScroll）
            DPI_OPTIONS.forEach { opt ->
                val isSel = selected == opt.scale
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow))
                        .clip(RoundedCornerShape(if (isSel) 12.dp else 8.dp))
                        .background(
                            if (isSel) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                        .clickable { selected = opt.scale },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "${opt.label}  x${opt.scale}",
                            fontSize = 10.sp,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSel) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        AnimatedVisibility(
                            visible = !isSel,
                            enter = ExpressiveMotion.EnterExpand,
                            exit = ExpressiveMotion.ExitShrink
                        ) {
                            Text(
                                opt.desc,
                                fontSize = 8.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isSel) {
                        Text("✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Spacer(Modifier.height(3.dp))
            }

            // 向下滑动提示 + 按钮在选项最下方
            Spacer(Modifier.height(6.dp))
            Text(
                "↓ 向下滑动",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 8.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))

            // 导航按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                TextButton(
                    onClick = { step = 0 },
                    modifier = Modifier.weight(1f).height(36.dp)
                ) { Text("上一步", fontSize = 10.sp) }
                Button(
                    onClick = {
                        scope.launch {
                            app.preferenceManager.saveDpiScale(selected)
                            app.preferenceManager.setDpiConfigured(true)
                            navController.navigate("login") {
                                popUpTo("dpi_setup") { inclusive = true }
                            }
                        }
                    },
                    interactionSource = finishBtnInteraction,
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .graphicsLayer {
                            scaleX = finishBtnScale
                            scaleY = finishBtnScale
                        },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("完成", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            // 圆屏底部留白：确保按钮完全可见
            Spacer(Modifier.height(if (isRound) 24.dp else 10.dp))
            }
        }
        }
    }
}

@Composable
private fun StepDot(active: Boolean, label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .padding(horizontal = 10.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepBar() {
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .padding(horizontal = 8.dp)
    )
}
