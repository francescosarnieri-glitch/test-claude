package com.cybersensei.academy.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NightColorScheme = darkColorScheme(
    primary = SenseiPalette.Mint,
    onPrimary = SenseiPalette.NightDeep,
    primaryContainer = SenseiPalette.MintDim,
    onPrimaryContainer = SenseiPalette.Mint,
    secondary = SenseiPalette.Amber,
    onSecondary = SenseiPalette.NightDeep,
    background = SenseiPalette.NightDeep,
    onBackground = SenseiPalette.TextNight,
    surface = SenseiPalette.NightSurface,
    onSurface = SenseiPalette.TextNight,
    surfaceVariant = SenseiPalette.NightSurfaceHigh,
    onSurfaceVariant = SenseiPalette.TextNightMuted,
    outline = SenseiPalette.NightOutline,
    error = SenseiPalette.Danger,
    onError = SenseiPalette.NightDeep,
)

private val DayColorScheme = lightColorScheme(
    primary = SenseiPalette.MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF3E3),
    onPrimaryContainer = Color(0xFF06301F),
    secondary = SenseiPalette.AmberDeep,
    onSecondary = Color.White,
    background = SenseiPalette.DayBackground,
    onBackground = SenseiPalette.TextDay,
    surface = SenseiPalette.DaySurface,
    onSurface = SenseiPalette.TextDay,
    surfaceVariant = SenseiPalette.DaySurfaceHigh,
    onSurfaceVariant = SenseiPalette.TextDayMuted,
    outline = SenseiPalette.DayOutline,
    error = SenseiPalette.DangerDeep,
    onError = Color.White,
)

private val SenseiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val LocalSenseiColors = staticCompositionLocalOf { NightSemanticColors }

/** Shorthand for the semantic colours: `SenseiTheme.colors.correct`. */
object SenseiTheme {
    val colors: SenseiSemanticColors
        @Composable get() = LocalSenseiColors.current
}

@Composable
fun CyberSenseiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately no dynamic colour: the palette carries meaning (mint = safe,
    // red = compromised) and must not be repainted by the device wallpaper.
    val colorScheme = if (darkTheme) NightColorScheme else DayColorScheme
    val semanticColors = if (darkTheme) NightSemanticColors else DaySemanticColors

    CompositionLocalProvider(LocalSenseiColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = SenseiTypography,
            shapes = SenseiShapes,
            content = content,
        )
    }
}
