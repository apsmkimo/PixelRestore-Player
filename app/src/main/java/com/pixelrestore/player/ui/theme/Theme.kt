package com.pixelrestore.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Background = Color(0xFF0E1116)
private val Surface = Color(0xFF171C24)
private val SurfaceVariant = Color(0xFF232A36)
private val Primary = Color(0xFF3DDC97)
private val OnPrimary = Color(0xFF003824)
private val Secondary = Color(0xFF9ECAFF)
private val OnSurface = Color(0xFFE6EAF0)

private val Colors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Secondary,
    onSecondary = Color(0xFF003256),
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Color(0xFFC4CAD4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun PixelRestoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(),
        content = content,
    )
}
