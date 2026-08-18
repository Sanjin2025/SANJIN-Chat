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
import com.sanjinchat.wear.ui.screen.LoginScreen
import com.sanjinchat.wear.ui.screen.MainScreen
import com.sanjinchat.wear.ui.screen.OnboardingScreen
import com.sanjinchat.wear.ui.theme.SanjinChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = SanjinChatApp.instance
            val dpiScale by app.preferenceManager.dpiScale.collectAsState(initial = 1.0f)

            SanjinChatTheme {
                // 应用用户在 Onboarding 选的字体大小（实时刷新）
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = LocalDensity.current.density,
                        fontScale = dpiScale
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

    val onboarded by app.preferenceManager.onboarded.collectAsState(initial = false)
    val token by app.preferenceManager.token.collectAsState(initial = null)
    val rememberLogin by app.preferenceManager.rememberLogin.collectAsState(initial = true)

    // 三态入口：
    // 1. 未完成首次设置 -> onboarding
    // 2. 已设置 + 有缓存 token + 用户允许记住登录 -> main（自动登录）
    // 3. 否则 -> login
    val startDest = when {
        !onboarded -> "onboarding"
        rememberLogin && !token.isNullOrBlank() -> "main"
        else -> "login"
    }

    NavHost(
        navController = navController,
        startDestination = startDest
    ) {
        composable("onboarding") {
            OnboardingScreen(navController = navController)
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
