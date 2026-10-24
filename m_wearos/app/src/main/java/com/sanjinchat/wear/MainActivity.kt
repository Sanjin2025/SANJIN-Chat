package com.sanjinchat.wear

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sanjinchat.wear.ui.screen.BootScreen
import com.sanjinchat.wear.ui.screen.ChatScreen
import com.sanjinchat.wear.ui.screen.ChatType
import com.sanjinchat.wear.ui.screen.DpiSetupScreen
import com.sanjinchat.wear.ui.screen.LoginScreen
import com.sanjinchat.wear.ui.screen.MainScreen
import com.sanjinchat.wear.ui.theme.ExpressiveMotion
import com.sanjinchat.wear.ui.theme.SanjinChatTheme
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // —— 系统级 Predictive Back（Android 13+）：返回按钮/边缘返回系统级预测动效 ——
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                // 用反射避免在低于 33 的 SDK 路径触发符号解析错误
                val dispatcher = android.app.Activity::class.java
                    .getMethod("getOnBackInvokedDispatcher")
                    .invoke(this)
                val callbackCls = Class.forName("android.window.OnBackInvokedCallback")
                val noop = java.lang.reflect.Proxy.newProxyInstance(
                    callbackCls.classLoader,
                    arrayOf(callbackCls)
                ) { _, _, _ -> null }
                dispatcher?.javaClass
                    ?.getMethod(
                        "registerOnBackInvokedCallback",
                        Int::class.javaPrimitiveType,
                        callbackCls
                    )
                    ?.invoke(dispatcher, 0 /* PRIORITY_DEFAULT */, noop)
            } catch (_: Throwable) { /* ROM 不支持 Predictive Back，忽略 */ }
        }
        // 安全：关闭 FLAG_ACTIVITY_CLEAR_TOP 时可能被遮挡问题 + 让 background 透明，露出 predictive back 底层
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false) } catch (_: Throwable) { /* noop */ }
        } else {
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        setContent {
            val app = SanjinChatApp.instance
            val dpiScale by app.preferenceManager.dpiScale.collectAsState(initial = 1.0f)

            SanjinChatTheme {
                // 真正的应用内 DPI 切换：同时缩放 dp 与 sp
                val baseDensity = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = (baseDensity * dpiScale).coerceIn(0.8f, 4.5f),
                        fontScale = dpiScale.coerceIn(0.5f, 2.0f)
                    )
                ) {
                    SanjinChatNavHost()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注意：不要调用 webSocketClient.destroy()！
        // destroy() 会 cancel 协程作用域，而从任务管理器划掉 app 后进程通常还存活，
        // 重开时 webSocketClient 被复用，scope 已取消会导致 connect() 后状态永远卡在
        // Connecting、登录超时（必须强行停止杀进程才能恢复）。
        // webSocketClient 是 Application 级单例，生命周期跟随进程，不由 Activity 管理。
        // 开了保活时连接应保持；没开保活时系统杀进程会自然清理。
    }
}

/**
 * 全局"预测性返回"手势层（Wear OS 全屏启用，不依赖系统 Predictive Back）
 *
 * 核心机制（修复之前"没起作用"的根因）：
 * - 用 PointerEventPass.Initial + awaitPointerEventScope 直接在 Initial 分发阶段拦截左边缘事件。
 *   这样内部 LazyColumn/Chip/Button 的 onClick/onDrag 不会先把横向手势"吃"掉，Predictive Back 绝对生效。
 * - 左边缘 20dp 内按下 → 横向位移为主 → 开始预测预览：跟手 translateX + scale 0.92 + alpha 0.65 + 圆角
 * - 拖动进度 > 40%（velocity 向右也可放宽到 35%）松手 → commitExit=true，spring 飞出 → popBackStack
 * - 否则 spring 回弹 0
 */
@Composable
fun PredictiveBackScaffold(
    navController: NavController,
    backStackEntry: NavBackStackEntry?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val backOwner = LocalOnBackPressedDispatcherOwner.current
    val canPop = enabled && (navController.previousBackStackEntry != null)

    var dragX by remember(backStackEntry) { mutableFloatStateOf(0f) }
    var commitExit by remember(backStackEntry) { mutableStateOf(false) }

    val layoutWidthPx = with(density) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    // 320dp 是基准 width；用实际屏宽避免小屏阈值不对
    val baseWidth = layoutWidthPx.coerceAtLeast(with(density) { 280.dp.toPx() })

    val animX by animateFloatAsState(
        targetValue = if (commitExit) baseWidth * 1.25f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
        label = "pbAnimX",
        finishedListener = { _ ->
            if (commitExit) {
                commitExit = false
                scope.launch {
                    val ok = runCatching { navController.popBackStack() }.getOrDefault(false)
                    if (!ok) runCatching { backOwner?.onBackPressedDispatcher?.onBackPressed() }
                }
            }
        }
    )
    val displayX = if (commitExit || dragX < 0.5f) animX else dragX
    val progress = (displayX.coerceIn(0f, baseWidth) / baseWidth).coerceIn(0f, 1f)
    val scale = 1f - progress * 0.10f    // 1.0 → 0.90（比之前更明显的 Expressive 收缩预览）
    val alpha = 1f - progress * 0.38f    // 1.0 → 0.62
    val cornerPx = progress * 34f

    val dragLayerModifier = Modifier
        .fillMaxSize()
        .then(
            if (canPop) Modifier.pointerInput(backStackEntry) {
                val edgeLimit = with(density) { 20.dp.toPx() }
                val touchSlop = 8.dp.toPx()
                awaitPointerEventScope {
                    outer@ while (true) {
                        // 1. 等待按下（Initial pass 读取，子节点还没 consume）
                        val down = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull()
                            ?: continue@outer
                        val startX = down.position.x
                        val startY = down.position.y
                        val fromEdge = startX < edgeLimit || startX < edgeLimit * 1.5f // 左边缘附近允许略宽一点（手表屏幕小）
                        var accepted = false
                        var accX = 0f
                        var accY = 0f
                        dragX = 0f

                        // 2. 监听移动（Initial pass）：一旦横向分量更大 + 向右拖 + fromEdge → 截获并开始预览
                        inner@ while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val ch: PointerInputChange = event.changes.firstOrNull() ?: break@inner
                            val posDelta = ch.positionChange()
                            val dx = posDelta.x
                            val dy = posDelta.y

                            if (!accepted) {
                                accX += dx
                                accY += dy
                                // 横向移动大于纵向 slop + 从边缘 + 向右
                                val axisOk = (abs(accX) > touchSlop) && (abs(accX) > abs(accY) * 1.2f)
                                if (axisOk && fromEdge && accX > 0f) {
                                    accepted = true
                                } else if ((abs(accX) > touchSlop * 2 || abs(accY) > touchSlop * 2) && !(fromEdge && accX > 0f)) {
                                    // 不是边缘右滑 → 交给子组件自己处理，直接等下一轮 down
                                    continue@outer
                                }
                            }

                            if (accepted) {
                                // Initial pass：先 consume，防止内部组件再拿这个横向滚动手势
                                if (dx != 0f || dy != 0f) {
                                    ch.consume()
                                }
                                val p = (dragX / baseWidth).coerceIn(0f, 1f)
                                // Expressive 阻尼：后半程越拖越"重"
                                val damp = 1f - p * 0.6f
                                dragX = (dragX + dx.coerceAtLeast(0f) * damp).coerceAtLeast(0f)
                            }

                            if (ch.pressed.not()) {
                                // 松手：根据进度 & 速度决定返回 or 回弹
                                if (accepted) {
                                    val p = (dragX / baseWidth).coerceIn(0f, 1f)
                                    // 进度 > 40% 或进度 > 25% 且当前拖拽速度正向明显 → 提交
                                    if (p >= 0.40f) {
                                        commitExit = true
                                    }
                                    dragX = 0f // 之后交给 animX（commit → 飞出 / 不 commit → 回弹 0）
                                }
                                break@inner
                            }
                        }
                    }
                }
            } else Modifier
        )
        .graphicsLayer {
            translationX = displayX
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
            shape = RoundedCornerShape(Dp(cornerPx / density.density))
            clip = progress > 0.01f
        }

    Box(modifier.fillMaxSize()) {
        // 背景
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        androidx.compose.animation.AnimatedVisibility(
            visible = progress > 0.01f,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(200, easing = ExpressiveMotion.EmphasizedDecelerate)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(120)
            ),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)))
        }
        Box(dragLayerModifier, contentAlignment = Alignment.Center) { content() }
    }
    // 未使用变量的 lint 抑制（保留避免以后扩展）
    @Suppress("UNUSED_EXPRESSION") Offset
}

@Composable
fun SanjinChatNavHost() {
    val navController = rememberNavController()

    // 入口统一为 boot（启动页）：由 BootScreen 用保存的 token 真实走一遍登录校验后再决定去 main 还是 login，
    // 避免"本地有 token 就直接进主界面"造成假登录（界面已登录、会话其实已失效）
    NavHost(
        navController = navController,
        startDestination = "boot"
    ) {
        composable("boot") { entry ->
            PredictiveBackScaffold(
                navController = navController,
                backStackEntry = entry,
                enabled = false // 启动页不允许返回
            ) {
                BootScreen(navController = navController)
            }
        }

        composable("dpi_setup") { entry ->
            PredictiveBackScaffold(
                navController = navController,
                backStackEntry = entry,
                enabled = false // DPI 是引导起点，无返回
            ) {
                DpiSetupScreen(navController = navController)
            }
        }

        composable("login") { entry ->
            PredictiveBackScaffold(
                navController = navController,
                backStackEntry = entry,
                enabled = false // login 是导航起点，不允许返回
            ) {
                LoginScreen(navController = navController)
            }
        }

        composable("main") { entry ->
            PredictiveBackScaffold(
                navController = navController,
                backStackEntry = entry,
                enabled = false // main 是主页面，右滑不应退出
            ) {
                MainScreen(navController = navController)
            }
        }

        composable(
            route = "chat/private/{friendId}/{friendName}",
            arguments = listOf(
                navArgument("friendId") { type = NavType.StringType },
                navArgument("friendName") { type = NavType.StringType }
            )
        ) { entry ->
            PredictiveBackScaffold(navController = navController, backStackEntry = entry) {
                val friendId = entry.arguments?.getString("friendId") ?: ""
                val friendName = entry.arguments?.getString("friendName") ?: ""
                ChatScreen(
                    navController = navController,
                    chatType = ChatType.Private(friendId, friendName)
                )
            }
        }

        composable(
            route = "chat/group/{groupId}/{groupName}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("groupName") { type = NavType.StringType }
            )
        ) { entry ->
            PredictiveBackScaffold(navController = navController, backStackEntry = entry) {
                val groupId = entry.arguments?.getString("groupId") ?: ""
                val groupName = entry.arguments?.getString("groupName") ?: ""
                ChatScreen(
                    navController = navController,
                    chatType = ChatType.Group(groupId, groupName)
                )
            }
        }
    }
}
