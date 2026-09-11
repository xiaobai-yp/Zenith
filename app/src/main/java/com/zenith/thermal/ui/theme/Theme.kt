package com.zenith.thermal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val ZenithColorScheme = darkColorScheme(
    primary = ZenithAccent,
    onPrimary = ZenithText,
    primaryContainer = ZenithSurface,
    onPrimaryContainer = ZenithText,
    secondary = ZenithAccent,
    onSecondary = ZenithText,
    secondaryContainer = ZenithSurface,
    onSecondaryContainer = ZenithText,
    background = ZenithBg,
    onBackground = ZenithText,
    surface = ZenithSurface,
    onSurface = ZenithText,
    surfaceVariant = ZenithSurface,
    onSurfaceVariant = ZenithMuted,
    outline = ZenithBorder,
    error = ZenithError,
    onError = ZenithText,
)

@Composable
fun ZenithTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = ZenithBg.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = ZenithColorScheme,
        typography = ZenithTypography,
        content = content,
    )
}
