// ==================== MD2 深色主题（Compose 统一配色） ====================
package com.sanjin.wear.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** MD2 配色：纯黑背景 + 靛蓝主色 + 深灰卡片 */
private val DarkColorPalette = darkColors(
    primary = Color(0xFF3F51B5),         // 主色 靛蓝
    primaryVariant = Color(0xFF303F9F),  // 暗色靛蓝
    secondary = Color(0xFF009688),       // 强调 青
    background = Color(0xFF000000),      // 纯黑（OLED 省电）
    surface = Color(0xFF1E1E1E),         // 卡片 深灰
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFF44336)
)

/**
 * 手表专用主题：强制深色 + 可选 UI 缩放（DPI 适配）
 * @param uiScale 缩放系数（0.85 / 1.0 / 1.15 / 1.3 等），1.0 表示原始尺寸
 */
@Composable
fun SanjinWearTheme(uiScale: Float = 1.0f, content: @Composable () -> Unit) {
    val baseDensity = LocalDensity.current
    val scaledDensity = Density(
        density = baseDensity.density * uiScale,
        fontScale = baseDensity.fontScale * uiScale
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(
            colors = DarkColorPalette,
            typography = WearTypography,
            shapes = WearShapes,
            content = content
        )
    }
}

// Typography（大字号，适合手表阅读）
private val WearTypography = Typography(
    h6 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    subtitle1 = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    body1 = TextStyle(fontSize = 14.sp),
    body2 = TextStyle(fontSize = 12.sp),
    button = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    caption = TextStyle(fontSize = 11.sp)
)

// Shapes
private val WearShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp)
)

// 常用颜色常量
object WearColors {
    val Online = Color(0xFF4CAF50)
    val Unread = Color(0xFFE91E63)
    val BubbleMe = Color(0xFF3F51B5)
    val BubbleOther = Color(0xFF2B2B2B)
    val SurfaceVariant = Color(0xFF2B2B2B)
    val TextSecondary = Color(0xFFB0B0B0)
    val TextHint = Color(0xFF757575)
    val Divider = Color(0xFF333333)
}
