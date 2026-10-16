package com.example.gdsviewer.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** True while the dark theme is active; lets any composable pick theme-dependent colors. */
val LocalDarkTheme = staticCompositionLocalOf { true }

/** Background of the drawing area (also used by the OpenGL renderer), ARGB. */
fun canvasColorInt(dark: Boolean): Int = if (dark) 0xFF101014.toInt() else 0xFFFAFAFA.toInt()

fun canvasColor(dark: Boolean): Color = Color(canvasColorInt(dark))

/** Material theme. Uses the wallpaper colors on Android 12+, the default Material colors before. */
@Composable
fun GdsViewerTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamic -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
