package com.sanjinchat.wear.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Material3 Dark Color Scheme (works on both Wear OS watches and phones)
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF2B2930),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF938F99)
)

// Helper colors (kept for backward-compatible references in screens)
val PurpleDeep: Color get() = DarkColorScheme.primaryContainer
val PurpleLight: Color get() = DarkColorScheme.onPrimaryContainer
val SurfaceVariant: Color get() = DarkColorScheme.surfaceVariant
val OnSurfaceVariant: Color get() = DarkColorScheme.onSurfaceVariant
val ErrorDeep: Color get() = DarkColorScheme.errorContainer
val ErrorLight: Color get() = DarkColorScheme.onErrorContainer
val OutlineGray: Color get() = DarkColorScheme.outline
val GreenOnline = Color(0xFF4CAF50)
val GrayOffline = Color(0xFF757575)

private val AppTypography = Typography()

@Composable
fun SanjinChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content
    )
}

/**
 * Material 3 Expressive 风格动效系统
 *
 * 用 Compose 现有 API 实现 Expressive 的核心动效特征：
 * - Emphasized Easing：大幅度的"快进慢出"曲线（0.2, 0, 0, 1）
 * - Spring 物理动画：形变/位移使用弹性弹簧
 * - 形变进入/退出：scale + fade + slide 组合
 */
object ExpressiveMotion {
    // —— Emphasized 缓动曲线 ——
    val EmphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    private const val DUR_MEDIUM = 300
    private const val DUR_LONG = 450
    private const val DUR_SHORT = 180

    // —— Spring 规格（形变、按压、列表项）——
    val SpringStandard get() = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
    val SpringBouncy get() = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    val SpringSnappy get() = spring<Float>(dampingRatio = 0.92f, stiffness = Spring.StiffnessHigh)

    /** 列表项/消息项出现/消失规格 */
    val itemEnterSpec get() = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
    val itemExitSpec get() = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)

    // —— 进入/退出过渡（Expressive 形变风格）——
    val EnterExpand: EnterTransition
        get() = fadeIn(tween(DUR_MEDIUM, easing = EmphasizedDecelerate)) +
            scaleIn(
                initialScale = 0.85f,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)
            )

    val ExitShrink: ExitTransition
        get() = fadeOut(tween(DUR_SHORT, easing = EmphasizedAccelerate)) +
            scaleOut(
                targetScale = 0.92f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            )

    val EnterSlideUp: EnterTransition
        get() = fadeIn(tween(DUR_MEDIUM, easing = EmphasizedDecelerate)) +
            slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = tween(DUR_LONG, easing = EmphasizedEasing)
            ) +
            scaleIn(
                initialScale = 0.9f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
            )

    val ExitSlideDown: ExitTransition
        get() = fadeOut(tween(DUR_SHORT, easing = EmphasizedAccelerate)) +
            slideOutVertically(
                targetOffsetY = { it / 6 },
                animationSpec = tween(DUR_MEDIUM, easing = EmphasizedEasing)
            ) +
            scaleOut(
                targetScale = 0.95f,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMedium)
            )

    val EnterSlideStart: EnterTransition
        get() = fadeIn(tween(DUR_MEDIUM, easing = EmphasizedDecelerate)) +
            slideInVertically(
                initialOffsetY = { it / 5 },
                animationSpec = tween(DUR_LONG, easing = EmphasizedEasing)
            )

    val ExitSlideEnd: ExitTransition
        get() = fadeOut(tween(DUR_SHORT, easing = EmphasizedAccelerate)) +
            slideOutVertically(
                targetOffsetY = { -it / 5 },
                animationSpec = tween(DUR_MEDIUM, easing = EmphasizedEasing)
            )

    /** 错误/警告抖动规格 */
    val ShakeEasing = CubicBezierEasing(0.36f, 0.07f, 0.19f, 0.97f)
}

/** 按压缩放规格（按钮按压时 scale → 0.95）*/
val PressScaleDown = 0.95f
val PressScaleSpring
    get() = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessHigh)
