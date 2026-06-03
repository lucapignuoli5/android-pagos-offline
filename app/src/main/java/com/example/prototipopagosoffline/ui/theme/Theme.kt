package com.example.prototipopagosoffline.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val FintechColorScheme = darkColorScheme(
    primary = NeonGreen,
    onPrimary = DeepBlue,
    background = DeepBlue,
    onBackground = White,
    surface = SurfaceBlue,
    onSurface = White,
    secondary = TextGray,
    error = RedExpense
)

@Composable
fun PrototipoPagosOfflineTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = FintechColorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = FintechColorScheme,
        typography = Typography,
        content = content
    )
}
