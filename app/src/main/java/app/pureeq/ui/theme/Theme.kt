package app.pureeq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The logo's colours: its five EQ bars run blue, lavender, white, lavender, coral from left to right,
 * on a deep navy. The app uses them sparingly: blue is the accent, the main EQ curve runs blue to
 * coral across the frequencies like the bars, and the ears take the two ends.
 */
object LogoColors {
    val Blue = Color(0xFF7FA7FF)
    val Lavender = Color(0xFFB7A4FF)
    val Coral = Color(0xFFFF8A8F)

    /** The same three, deep enough to read on the light theme's pale surfaces. */
    val BlueDeep = Color(0xFF3A5EB2)
    val LavenderDeep = Color(0xFF6E5BC2)
    val CoralDeep = Color(0xFFC24A5A)
}

/** The logo's bar colours, low to high, for the theme in use. */
@Composable
fun logoGradient(): List<Color> =
    if (isSystemInDarkTheme()) listOf(LogoColors.Blue, LogoColors.Lavender, LogoColors.Coral)
    else listOf(LogoColors.BlueDeep, LogoColors.LavenderDeep, LogoColors.CoralDeep)

/** Left ear the logo's blue, right ear its coral, between its light and deep shades so both themes read them. */
object EarColors {
    val Left = Color(0xFF6B97F7)
    val Right = Color(0xFFF2777F)
}

/** Warnings and errors only; anything good or switched on uses the accent ([MaterialTheme]'s primary). */
object StatusColors {
    val Warn = Color(0xFFF5B84B)
    val Bad = Color(0xFFFF7A7A)
}

/*
 * Calm by design: graphite neutrals with a faint navy from the logo's background, and the logo's
 * blue for what's on or chosen (switches, the main button, sliders, the selected mode's outline).
 * Tonal buttons, tracks and the selected mode's fill stay neutral, so colour marks state instead
 * of covering the screen.
 */
private val Dark = darkColorScheme(
    primary = LogoColors.Blue,
    onPrimary = Color(0xFF0B1A3A),
    primaryContainer = Color(0xFF1B2131),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFFAEB4C0),
    onSecondary = Color(0xFF1A1D24),
    secondaryContainer = Color(0xFF23262E),
    onSecondaryContainer = Color(0xFFE3E6EC),
    tertiary = LogoColors.Coral,
    onTertiary = Color(0xFF3A0A10),
    background = Color(0xFF0D0F14),
    onBackground = Color(0xFFE9EAEE),
    surface = Color(0xFF0D0F14),
    onSurface = Color(0xFFE9EAEE),
    surfaceVariant = Color(0xFF1F232B),
    onSurfaceVariant = Color(0xFFA3A8B3),
    surfaceContainerLowest = Color(0xFF090B0F),
    surfaceContainerLow = Color(0xFF121419),
    surfaceContainer = Color(0xFF16191F),
    surfaceContainerHigh = Color(0xFF1C1F26),
    surfaceContainerHighest = Color(0xFF23262E),
    outline = Color(0xFF3A3E48),
    outlineVariant = Color(0xFF262A32),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
)

private val Light = lightColorScheme(
    primary = LogoColors.BlueDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5ECFA),
    onPrimaryContainer = Color(0xFF10244F),
    secondary = Color(0xFF5D6470),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E8ED),
    onSecondaryContainer = Color(0xFF1B1E24),
    tertiary = LogoColors.CoralDeep,
    onTertiary = Color.White,
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFF6F7F9),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE3E5EA),
    onSurfaceVariant = Color(0xFF50555F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF0F3),
    surfaceContainer = Color(0xFFEAECEF),
    surfaceContainerHigh = Color(0xFFE4E6EA),
    surfaceContainerHighest = Color(0xFFDEE0E5),
    outline = Color(0xFFB5B9C2),
    outlineVariant = Color(0xFFD7DAE0),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Monospace-free tabular figures for dB values. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun PureEqTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        content = content,
    )
}
