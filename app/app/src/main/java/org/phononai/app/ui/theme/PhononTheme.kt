package org.phononai.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PhononColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF0A0F16)
    val SurfaceHigh = Color(0xFF101724)
    val Border = Color(0xFF1C2A3A)
    val Text = Color(0xFFE6EDF7)
    val TextDim = Color(0xFF8B98AC)
    val Cyan = Color(0xFF00E5FF)
    val Violet = Color(0xFF8B5CF6)
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Danger = Color(0xFFF87171)
    val DimCyan = Color(0xFF5FA8C4)
    val Mono = FontFamily.Monospace
}

private val Scheme = darkColorScheme(
    primary = PhononColors.Cyan,
    onPrimary = Color(0xFF001318),
    secondary = PhononColors.Violet,
    onSecondary = Color.White,
    tertiary = PhononColors.Warn,
    background = PhononColors.Background,
    onBackground = PhononColors.Text,
    surface = PhononColors.Surface,
    onSurface = PhononColors.Text,
    surfaceVariant = PhononColors.SurfaceHigh,
    onSurfaceVariant = PhononColors.TextDim,
    outline = PhononColors.Border,
    error = PhononColors.Danger,
    onError = Color(0xFF200404),
)

private val Type = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.9.sp,
    ),
)

private val PhononShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun PhononTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        typography = Type,
        shapes = PhononShapes,
        content = content,
    )
}
