package com.olyphototagger.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// A deep teal, not Material3's stock purple-blue — chosen to read as its own product
// rather than "generic Android app," and deliberately not Olympus's own blue: this app
// has no affiliation with Olympus/OM System, and borrowing their brand color would
// misleadingly suggest otherwise. Values are a single 7-stop ramp (50/100/.../900) so
// light and dark containers stay visually related instead of picked independently.
private val Teal50 = Color(0xFFE1F5EE)
private val Teal100 = Color(0xFF9FE1CB)
private val Teal200 = Color(0xFF5DCAA5)
private val Teal600 = Color(0xFF0F6E56)
private val Teal800 = Color(0xFF085041)
private val Teal900 = Color(0xFF04342C)

private val DarkColorScheme = darkColorScheme(
    primary = Teal200,
    onPrimary = Teal900,
    primaryContainer = Teal800,
    onPrimaryContainer = Teal50,
    secondaryContainer = Teal900,
    onSecondaryContainer = Teal100
)
private val LightColorScheme = lightColorScheme(
    primary = Teal600,
    onPrimary = Color.White,
    primaryContainer = Teal50,
    onPrimaryContainer = Teal900,
    secondaryContainer = Teal50,
    onSecondaryContainer = Teal800
)

@Composable
fun OlyPhotoTaggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default so the palette above actually shows up — with this on (and it was,
    // production's only call site never passed a value) every user instead sees whatever
    // Android's Material You derives from their own wallpaper, not this app's own
    // branding. Previews already always pass false explicitly; this changes what a real
    // install gets.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
