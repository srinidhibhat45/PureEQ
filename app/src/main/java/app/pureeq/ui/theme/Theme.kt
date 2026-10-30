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

/** Left ear blue, right ear red. */
object EarColors {
    val Left = Color(0xFF5AA2FF)
    val Right = Color(0xFFFF6B7A)
}

object StatusColors {
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFF5B84B)
    val Bad = Color(0xFFFF7A7A)
}

private val Dark = darkColorScheme(
    primary = Color(0xFFAEA2FF),
    onPrimary = Color(0xFF1C1548),
    primaryContainer = Color(0xFF2E2766),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFF62D6C6),
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF173B37),
    onSecondaryContainer = Color(0xFFBDF3EA),
    tertiary = Color(0xFFFFBE7A),
    onTertiary = Color(0xFF2E1600),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE7E9F0),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = Color(0xFF1B2130),
    onSurfaceVariant = Color(0xFFB2B8C6),
    surfaceContainerLowest = Color(0xFF07090E),
    surfaceContainerLow = Color(0xFF10141D),
    surfaceContainer = Color(0xFF141925),
    surfaceContainerHigh = Color(0xFF1A202E),
    surfaceContainerHighest = Color(0xFF222939),
    outline = Color(0xFF3B4254),
    outlineVariant = Color(0xFF262C3B),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF3B0907),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4BD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E0FF),
    onPrimaryContainer = Color(0xFF1C1260),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFEA),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFFB4610C),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF151821),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF151821),
    surfaceVariant = Color(0xFFE6E8F0),
    onSurfaceVariant = Color(0xFF4A5060),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F2F8),
    surfaceContainer = Color(0xFFEBEDF5),
    surfaceContainerHigh = Color(0xFFE5E7F0),
    surfaceContainerHighest = Color(0xFFDFE2EC),
    outline = Color(0xFFB7BCCB),
    outlineVariant = Color(0xFFD6D9E3),
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
