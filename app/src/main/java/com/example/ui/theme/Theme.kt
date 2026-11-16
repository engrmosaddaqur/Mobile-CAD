package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CadDarkColorScheme = darkColorScheme(
    primary = CadCyan,
    onPrimary = Color(0xFF002025),
    primaryContainer = CadCyanContainer,
    onPrimaryContainer = CadCyanOnContainer,
    secondary = CadAmber,
    onSecondary = Color(0xFF281900),
    secondaryContainer = CadAmberContainer,
    onSecondaryContainer = CadAmberOnContainer,
    tertiary = CadEmerald,
    onTertiary = Color(0xFF00210D),
    tertiaryContainer = CadEmeraldContainer,
    onTertiaryContainer = Color(0xFF9DF8BA),
    background = CadObsidian,
    onBackground = CadTextPrimaryDark,
    surface = CadSurfaceDark,
    onSurface = CadTextPrimaryDark,
    surfaceVariant = CadSurfaceElevated,
    onSurfaceVariant = CadTextSecondaryDark,
    outline = CadBorderDark,
    outlineVariant = Color(0xFF1F2B3D),
    error = CadCoral,
    onError = Color(0xFF3E0003),
    errorContainer = CadCoralContainer,
    onErrorContainer = Color(0xFFFFDAD6)
)

private val CadLightColorScheme = lightColorScheme(
    primary = CadBluePrimaryLight,
    onPrimary = Color.White,
    primaryContainer = CadBlueContainerLight,
    onPrimaryContainer = Color(0xFF001F29),
    secondary = CadAmberLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB3),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF006D3B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFA1F6B9),
    onTertiaryContainer = Color(0xFF00210E),
    background = CadPaperLight,
    onBackground = CadTextPrimaryLight,
    surface = CadSurfaceLight,
    onSurface = CadTextPrimaryLight,
    surfaceVariant = CadSurfaceVariantLight,
    onSurfaceVariant = CadTextSecondaryLight,
    outline = CadBorderLight,
    outlineVariant = Color(0xFFDDE5EF),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

enum class AppThemeMode {
    DARK, LIGHT, SYSTEM
}

@Composable
fun MobileCadTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colorScheme = if (useDark) CadDarkColorScheme else CadLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MobileCadTypography,
        content = content
    )
}
