package com.perezfranco.balrog.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ember = Color(0xFFFF7A00)
private val EmberDim = Color(0xFFB35600)
private val Coal = Color(0xFF121212)
private val Ash = Color(0xFF1E1B18)

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color.Black,
    secondary = Color(0xFFFFB877),
    background = Coal,
    surface = Ash,
    surfaceVariant = Color(0xFF2A2622),
)

private val LightColors = lightColorScheme(
    primary = EmberDim,
    onPrimary = Color.White,
    secondary = Color(0xFF8A4B12),
)

@Composable
fun BalrogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
