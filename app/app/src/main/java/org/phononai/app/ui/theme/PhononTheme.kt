package org.phononai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.phononai.app.R

/**
 * CORE: the thermal-readout identity. Two grounds, one set of tokens.
 *
 * Every colour in the app comes from here, so the light palette is a real
 * second theme rather than a filter over the dark one.
 */
@Immutable
data class Palette(
    val ground: Color,
    val surface: Color,
    val surfaceHigh: Color,
    /** Sunken panels: tab bar, key boxes, field wells. */
    val inset: Color,
    val ink: Color,
    val dim: Color,
    val rule: Color,
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val ok: Color,
    val warn: Color,
    val danger: Color,
    /** Cold to hot, four bands. Brand furniture, not a data channel. */
    val heat: List<Color>,
    val dark: Boolean,
)

val CoreDark = Palette(
    ground = Color(0xFF08070A),
    surface = Color(0xFF121016),
    surfaceHigh = Color(0xFF1B1620),
    inset = Color(0xFF08070A),
    ink = Color(0xFFFFF7F0),
    dim = Color(0xFFA79AA6),
    rule = Color(0xFF241F2B),
    primary = Color(0xFFFFD166),
    onPrimary = Color(0xFF08070A),
    accent = Color(0xFFC2185B),
    onAccent = Color(0xFFFFF7F0),
    ok = Color(0xFF9BC53D),
    warn = Color(0xFFFFB020),
    danger = Color(0xFFFF5A5F),
    heat = listOf(Color(0xFF3B1F6E), Color(0xFFC2185B), Color(0xFFFF6B00), Color(0xFFFFD166)),
    dark = true,
)

val CoreLight = Palette(
    ground = Color(0xFFFFF8F2),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF6ECE4),
    // On paper the sunken panel is the darker cream, not the page itself.
    inset = Color(0xFFF6ECE4),
    ink = Color(0xFF1A1210),
    dim = Color(0xFF6F5F58),
    rule = Color(0xFFEADDD4),
    primary = Color(0xFFC2185B),
    onPrimary = Color(0xFFFFF8F2),
    accent = Color(0xFFEF6C00),
    onAccent = Color(0xFFFFF8F2),
    ok = Color(0xFF3F6212),
    warn = Color(0xFFB45309),
    danger = Color(0xFFB3122E),
    heat = listOf(Color(0xFF6C4AE8), Color(0xFFD81B60), Color(0xFFEF6C00), Color(0xFFFFC043)),
    dark = false,
)

val LocalPalette = staticCompositionLocalOf { CoreDark }

/**
 * The names the screens already use, resolved from the palette in scope.
 *
 * These are composable getters: a call site written for the old dark-only
 * object (PhononColors.Cyan) still compiles, but now reads the active theme.
 * Anything outside a composable must capture the colour first — see accentFor
 * and the Canvas scopes in PhononApp.
 */
object PhononColors {
    val Background: Color @Composable get() = LocalPalette.current.ground

    /** Sunken panel: tab bar, key boxes, field wells. */
    val Inset: Color @Composable get() = LocalPalette.current.inset
    val Surface: Color @Composable get() = LocalPalette.current.surface
    val SurfaceHigh: Color @Composable get() = LocalPalette.current.surfaceHigh
    val Border: Color @Composable get() = LocalPalette.current.rule
    val Text: Color @Composable get() = LocalPalette.current.ink
    val TextDim: Color @Composable get() = LocalPalette.current.dim
    val Cyan: Color @Composable get() = LocalPalette.current.primary
    val OnCyan: Color @Composable get() = LocalPalette.current.onPrimary
    val Violet: Color @Composable get() = LocalPalette.current.accent
    val Ok: Color @Composable get() = LocalPalette.current.ok
    val Warn: Color @Composable get() = LocalPalette.current.warn
    val Danger: Color @Composable get() = LocalPalette.current.danger

    /** Stopped state: present but receded. */
    val DimCyan: Color @Composable get() = LocalPalette.current.dim
    val Heat: List<Color> @Composable get() = LocalPalette.current.heat
}

// ---------------------------------------------------------------------------
// Type: Archivo Black for display, Inter for body, IBM Plex Mono for numbers
// and micro-labels.
// ---------------------------------------------------------------------------

private val ArchivoBlack = FontFamily(Font(R.font.archivo_black, FontWeight.Normal))

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

/**
 * Named styles for what the theme scale cannot express: a section heading is
 * uppercase display type, a measurement is tabular mono, a micro-label is
 * letterspaced mono. Uppercasing is done at the call site (Compose has no
 * text-transform), so the words stay visible in the source.
 */
object CoreType {
    val mono: FontFamily = PlexMono

    val display = TextStyle(
        fontFamily = ArchivoBlack,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    )
    val headline = TextStyle(
        fontFamily = ArchivoBlack,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.3).sp,
    )
    val section = TextStyle(
        fontFamily = ArchivoBlack,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.2.sp,
    )

    /** A measurement. Mono is tabular by construction. */
    val metric = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.5).sp,
    )
    /**
     * A word in the measurement slot ("IDLE"). The display face, not the mono:
     * Plex Mono's lowercase i and l carry baseline serifs, so a lowercase word at
     * 26 sp reads as a serif metric next to the digits. Words are set in the face
     * the rest of the readout shouts in.
     */
    val metricWord = display.copy(
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.6.sp,
    )
    val micro = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.9.sp,
    )
    val microSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.1.sp,
    )
}

private val CoreTypography = Typography(
    displayLarge = CoreType.display,
    displayMedium = CoreType.display.copy(fontSize = 28.sp, lineHeight = 29.sp),
    displaySmall = CoreType.display.copy(fontSize = 24.sp, lineHeight = 26.sp),
    headlineLarge = CoreType.display.copy(fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
    headlineMedium = CoreType.headline,
    headlineSmall = CoreType.metric.copy(fontSize = 20.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(
        fontFamily = ArchivoBlack,
        fontSize = 18.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = CoreType.section,
    titleSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Inter,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        // Every figure in body text lines up in a column.
        fontFeatureSettings = "tnum",
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    ),
    bodySmall = TextStyle(
        fontFamily = Inter,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontFeatureSettings = "tnum",
    ),
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = CoreType.micro,
    labelSmall = CoreType.microSmall,
)

/** Square. Every radius in the product is zero. */
private val Square = RoundedCornerShape(0.dp)

private val PhononShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

private fun Palette.scheme() = if (dark) {
    darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        secondary = accent,
        onSecondary = onAccent,
        tertiary = warn,
        onTertiary = ground,
        background = ground,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = dim,
        outline = rule,
        outlineVariant = rule,
        error = danger,
        onError = ground,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
    )
} else {
    lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        secondary = accent,
        onSecondary = onAccent,
        tertiary = warn,
        onTertiary = onAccent,
        background = ground,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = dim,
        outline = rule,
        outlineVariant = rule,
        error = danger,
        onError = onAccent,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
    )
}

@Composable
fun PhononTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) CoreDark else CoreLight
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = palette.scheme(),
            typography = CoreTypography,
            shapes = PhononShapes,
            content = content,
        )
    }
}
