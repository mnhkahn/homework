package com.homeworkbuddy

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class HomeColors(
    val sky: Color = Color(0xFFE5E7D4),
    val sun: Color = Color(0xFFEDE2C6),
    val leaf: Color = Color(0xFFDDE6CF),
    val primary: Color = Color(0xFF51653B),
    val timer: Color = Color(0xFFF2EAD6),
)

internal val LocalHomeColors = staticCompositionLocalOf { HomeColors() }

@Composable
internal fun HomeTheme(content: @Composable () -> Unit) {
    val colors = HomeColors()
    val scheme = lightColorScheme(
        primary = colors.primary,
        onPrimary = Color(0xFFFFF8E8),
        primaryContainer = Color(0xFFDDE6CF),
        onPrimaryContainer = Color(0xFF25351B),
        secondary = Color(0xFF666044),
        secondaryContainer = Color(0xFFE7DFC4),
        onSecondaryContainer = Color(0xFF35321F),
        background = Color(0xFFEFE7D2),
        onBackground = Color(0xFF30352B),
        surface = Color(0xFFF2EAD6),
        onSurface = Color(0xFF30352B),
        surfaceVariant = Color(0xFFE5DEC9),
        onSurfaceVariant = Color(0xFF565A4C),
        outline = Color(0xFF77796A),
    )
    CompositionLocalProvider(LocalHomeColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
