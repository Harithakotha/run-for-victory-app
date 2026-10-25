package com.runforvictory.tuition.core.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = BrandNavyPrimary,
    onPrimary = SurfaceLight,
    primaryContainer = BrandNavyLight,
    onPrimaryContainer = SurfaceLight,
    secondary = BrandTealPrimary,
    onSecondary = SurfaceLight,
    secondaryContainer = BrandTealLight,
    onSecondaryContainer = SurfaceLight,
    tertiary = BrandGoldPrimary,
    onTertiary = SurfaceLight,
    tertiaryContainer = BrandGoldLight,
    onTertiaryContainer = SurfaceLight,
    background = BackgroundLight,
    onBackground = TextPrimaryLight,
    surface = SurfaceLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = BackgroundLight,
    onSurfaceVariant = TextSecondaryLight
)

private val DarkColorScheme = darkColorScheme(
    primary = BrandTealLight,
    onPrimary = BrandNavyDark,
    primaryContainer = BrandNavyPrimary,
    onPrimaryContainer = SurfaceLight,
    secondary = BrandTealPrimary,
    onSecondary = SurfaceLight,
    tertiary = BrandGoldLight,
    onTertiary = BrandNavyDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceDark,
    onSurfaceVariant = TextSecondaryDark
)

@Composable
fun RunForVictoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.primary.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
