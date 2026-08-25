package com.example.ui.theme

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

private val DarkColorScheme =
  darkColorScheme(
    primary = NcDarkPrimary,
    onPrimary = Color(0xFF003253),
    primaryContainer = Color(0xFF004975),
    onPrimaryContainer = Color(0xFFCCE5FF),
    secondary = NcCyanAccent,
    onSecondary = Color(0xFF003642),
    secondaryContainer = Color(0xFF004E5F),
    onSecondaryContainer = Color(0xFFBBE9FF),
    tertiary = NcSuccessGreen,
    background = NcDarkBackground,
    onBackground = Color(0xFFE2E8F0),
    surface = NcDarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = NcDarkCard,
    onSurfaceVariant = Color(0xFF94A3B8)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = NcPrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = NcLightBlue,
    onPrimaryContainer = NcDarkBlue,
    secondary = NcCyanAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F7FA),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary = NcSuccessGreen,
    background = NcLightBackground,
    onBackground = Color(0xFF0F172A),
    surface = NcLightSurface,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF2F6),
    onSurfaceVariant = Color(0xFF475569)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }
      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

