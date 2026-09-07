package com.example.convert2video.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Tokens without a Material3 ColorScheme slot (design doc's ink3 / ok / dashed dropzone bg / warning badge). */
data class C2vExtendedColors(
    val ink3: Color,
    val ok: Color,
    val cardBorder: Color,
    val dropZoneBackground: Color,
    /** 에러 로그 W 배지 컨테이너 색상. */
    val warningContainer: Color,
    /** 에러 로그 W 배지 텍스트 색상. */
    val onWarningContainer: Color,
)

private val LocalC2vExtendedColors = staticCompositionLocalOf {
    C2vExtendedColors(
        ink3 = C2vDark.ink3,
        ok = C2vDark.ok,
        cardBorder = C2vDark.line,
        dropZoneBackground = C2vDark.accentSoft,
        warningContainer = C2vDark.warningContainer,
        onWarningContainer = C2vDark.onWarningContainer,
    )
}

/** Access non-ColorScheme design tokens, e.g. `C2vTheme.colors.ink3`. */
object C2vTheme {
    val colors: C2vExtendedColors
        @Composable get() = LocalC2vExtendedColors.current
}

private fun darkScheme() = darkColorScheme(
    primary = C2vDark.accent,
    onPrimary = C2vDark.accentInk,
    primaryContainer = C2vDark.accentSoft,
    onPrimaryContainer = C2vDark.ink,
    secondary = C2vDark.ink2,
    onSecondary = C2vDark.bg,
    secondaryContainer = C2vDark.surface2,
    onSecondaryContainer = C2vDark.ink,
    tertiary = C2vDark.ok,
    onTertiary = C2vDark.bg,
    background = C2vDark.bg,
    onBackground = C2vDark.ink,
    surface = C2vDark.surface,
    onSurface = C2vDark.ink,
    surfaceVariant = C2vDark.surface2,
    onSurfaceVariant = C2vDark.ink2,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = C2vDark.bg,
    surfaceContainerLow = C2vDark.surface,
    surfaceContainer = C2vDark.surface,
    surfaceContainerHigh = C2vDark.surface2,
    surfaceContainerHighest = C2vDark.surface2,
    surfaceBright = C2vDark.surface2,
    surfaceDim = C2vDark.bg,
    inverseSurface = C2vDark.ink,
    inverseOnSurface = C2vDark.bg,
    inversePrimary = C2vLight.accent,
    error = C2vDark.danger,
    onError = C2vDark.bg,
    errorContainer = C2vDark.danger.copy(alpha = 0.18f),
    onErrorContainer = C2vDark.ink,
    outline = C2vDark.line,
    outlineVariant = C2vDark.line,
    scrim = C2vDark.scrim,
)

private fun lightScheme() = lightColorScheme(
    primary = C2vLight.accent,
    onPrimary = C2vLight.accentInk,
    primaryContainer = C2vLight.accentSoft,
    onPrimaryContainer = C2vLight.ink,
    secondary = C2vLight.ink2,
    onSecondary = C2vLight.bg,
    secondaryContainer = C2vLight.surface2,
    onSecondaryContainer = C2vLight.ink,
    tertiary = C2vLight.ok,
    onTertiary = C2vLight.bg,
    background = C2vLight.bg,
    onBackground = C2vLight.ink,
    surface = C2vLight.surface,
    onSurface = C2vLight.ink,
    surfaceVariant = C2vLight.surface2,
    onSurfaceVariant = C2vLight.ink2,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = C2vLight.bg,
    surfaceContainerLow = C2vLight.surface,
    surfaceContainer = C2vLight.surface,
    surfaceContainerHigh = C2vLight.surface2,
    surfaceContainerHighest = C2vLight.surface2,
    surfaceBright = C2vLight.surface,
    surfaceDim = C2vLight.bg,
    inverseSurface = C2vLight.ink,
    inverseOnSurface = C2vLight.bg,
    inversePrimary = C2vDark.accent,
    error = C2vLight.danger,
    onError = C2vLight.surface,
    errorContainer = C2vLight.danger.copy(alpha = 0.14f),
    onErrorContainer = C2vLight.ink,
    outline = C2vLight.line,
    outlineVariant = C2vLight.line,
    scrim = C2vLight.scrim,
)

private val DarkExtendedColors = C2vExtendedColors(
    ink3 = C2vDark.ink3,
    ok = C2vDark.ok,
    cardBorder = C2vDark.line,
    dropZoneBackground = C2vDark.accentSoft,
    warningContainer = C2vDark.warningContainer,
    onWarningContainer = C2vDark.onWarningContainer,
)

private val LightExtendedColors = C2vExtendedColors(
    ink3 = C2vLight.ink3,
    ok = C2vLight.ok,
    cardBorder = C2vLight.line,
    dropZoneBackground = C2vLight.accentSoft,
    warningContainer = C2vLight.warningContainer,
    onWarningContainer = C2vLight.onWarningContainer,
)

@Composable
fun Convert2videoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You dynamic color would override the app's honey/cocoa brand palette — always off.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) darkScheme() else lightScheme()
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalC2vExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = C2vShapes,
            content = content
        )
    }
}
