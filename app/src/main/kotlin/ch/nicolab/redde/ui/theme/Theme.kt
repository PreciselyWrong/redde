package ch.nicolab.redde.ui.theme

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

private val Graphite = Color(0xFF232A37)
private val Spark = Color(0xFFE8B04B)

private val Light = lightColorScheme(primary = Graphite, secondary = Spark, background = Color(0xFFF6F7FA))
private val Dark = darkColorScheme(primary = Color(0xFFE4E9F1), secondary = Spark, background = Color(0xFF141A26))

/** Placeholder until the palettes of the iOS Theme.swift are ported (phase 3). */
@Composable
fun ReddeTheme(dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
