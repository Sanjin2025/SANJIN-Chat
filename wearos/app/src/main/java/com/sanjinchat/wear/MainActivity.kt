package com.sanjinchat.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sanjinchat.wear.ui.screen.ChatScreen
import com.sanjinchat.wear.ui.screen.ChatType
import com.sanjinchat.wear.ui.screen.DpiSetupScreen
import com.sanjinchat.wear.ui.screen.LoginScreen
import com.sanjinchat.wear.ui.screen.MainScreen
import com.sanjinchat.wear.ui.theme.SanjinChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        SanjinChatApp.instance.webSocketClient.destroy()
    }
}

@Composable
fun SanjinChatNavHost() {
    val navController = rememberNavController()
    val app = SanjinChatApp.instance

    val token by app.preferenceManager.token.collectAsState(initial = null)
    val rememberLogin by app.preferenceManager.rememberLogin.collectAsState(initial = true)
    val dpiConfigured by app.preferenceManager.dpiConfigured.collectAsState(initial = false)

    // 入口三态：
    // 1. 首次启动（未配置 DPI）→ dpi_setup（选择显示密度）
    // 2. 有缓存 token + 记住登录 → main（自动登录）
    // 3. 否则 → login
    val startDest = when {
        !dpiConfigured -> "dpi_setup"
        rememberLogin && !token.isNullOrBlank() -> "main"
        else -> "login"
    }

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable("dpi_setup") {
            DpiSetupScreen(navController = navController)
        }

        composable("login") {
            LoginScreen(navController = navController)
        }

        composable("main") {
            MainScreen(navController = navController)
        }

        composable(
            route = "chat/private/{friendId}/{friendName}",
            arguments = listOf(
                navArgument("friendId") { type = NavType.StringType },
                navArgument("friendName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val friendId = backStackEntry.arguments?.getString("friendId") ?: ""
            val friendName = backStackEntry.arguments?.getString("friendName") ?: ""
            ChatScreen(
                navController = navController,
                chatType = ChatType.Private(friendId, friendName)
            )
        }

        composable(
            route = "chat/group/{groupId}/{groupName}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType },
                navArgument("groupName") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getString("groupId") ?: ""
            val groupName = backStackEntry.arguments?.getString("groupName") ?: ""
            ChatScreen(
                navController = navController,
                chatType = ChatType.Group(groupId, groupName)
            )
        }
    }
}
