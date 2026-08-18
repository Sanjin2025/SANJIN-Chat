package com.sanjinchat.wear.ui.theme

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
