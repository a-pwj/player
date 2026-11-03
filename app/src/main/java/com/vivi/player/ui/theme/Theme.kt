package com.vivi.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.vivi.core.data.model.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3B0E00),
    secondary = Color(0xFFFFB59E),
    background = Color(0xFF101014),
    surface = Color(0xFF16161C),
    surfaceVariant = Color(0xFF232329),
    onSurface = Color(0xFFECECEC),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF3A3A42),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFB4340A),
    secondary = Color(0xFF8A4A2E),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B1F),
)

private val AmoledColors = DarkColors.copy(
    background = Color.Black,
    surface = Color(0xFF0A0A0C),
    surfaceVariant = Color(0xFF141418),
)

/**
 * 主题。播放器界面基本只有黑色叠加层，因此浅色/深色差异主要体现在媒体库；
 * AMOLED 单独一档，让 OLED 屏在播放时真正关屏像素省电。
 */
@Composable
fun ViivTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        mode == ThemeMode.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
