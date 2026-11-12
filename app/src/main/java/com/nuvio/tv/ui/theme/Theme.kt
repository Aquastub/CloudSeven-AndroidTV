package com.nuvio.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.nuvio.tv.domain.model.AppFont
import com.nuvio.tv.domain.model.AppTheme
import com.nuvio.tv.core.theme.ThemePackage

data class NuvioExtendedColors(
    val backgroundElevated: Color,
    val backgroundCard: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val focusRing: Color,
    val focusBackground: Color,
    val rating: Color
)

val LocalNuvioColors = staticCompositionLocalOf {
    NuvioColorScheme(ThemeColors.Ocean)
}

val LocalNuvioExtendedColors = staticCompositionLocalOf {
    NuvioExtendedColors(
        backgroundElevated = Color(0xFF1A1A1A),
        backgroundCard = Color(0xFF242424),
        textSecondary = Color(0xFFB3B3B3),
        textTertiary = Color(0xFF808080),
        focusRing = ThemeColors.Ocean.focusRing,
        focusBackground = ThemeColors.Ocean.focusBackground,
        rating = Color(0xFFFFD700)
    )
}

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }
val LocalC7ThemePackage = staticCompositionLocalOf<ThemePackage?> { null }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun NuvioTheme(
    appTheme: AppTheme = AppTheme.WHITE,
    appFont: AppFont = AppFont.INTER,
    customTheme: ThemePackage? = null,
    content: @Composable () -> Unit
) {
    val basePalette = ThemeColors.getColorPalette(appTheme)
    val palette = customTheme?.let { theme ->
        basePalette.copy(
            secondary = Color(theme.color("accent", basePalette.secondary.toArgbCompat())),
            secondaryVariant = Color(theme.color("accentSecondary", basePalette.secondaryVariant.toArgbCompat())),
            focusRing = Color(theme.color("focus", basePalette.focusRing.toArgbCompat())),
            focusBackground = Color(theme.color("focusBackground", basePalette.focusBackground.toArgbCompat())),
            background = Color(theme.color("background", basePalette.background.toArgbCompat())),
            backgroundElevated = Color(theme.color("surface", basePalette.backgroundElevated.toArgbCompat())),
            backgroundCard = Color(theme.color("card", basePalette.backgroundCard.toArgbCompat()))
        )
    } ?: basePalette
    val colorScheme = NuvioColorScheme(palette)

    val materialColorScheme = darkColorScheme(
        primary = colorScheme.Primary,
        onPrimary = colorScheme.OnPrimary,
        secondary = colorScheme.Secondary,
        onSecondary = colorScheme.OnSecondary,
        background = colorScheme.Background,
        surface = colorScheme.Surface,
        surfaceVariant = colorScheme.SurfaceVariant,
        onBackground = colorScheme.TextPrimary,
        onSurface = colorScheme.TextPrimary,
        onSurfaceVariant = colorScheme.TextSecondary,
        error = colorScheme.Error
    )

    val extendedColors = NuvioExtendedColors(
        backgroundElevated = colorScheme.BackgroundElevated,
        backgroundCard = colorScheme.BackgroundCard,
        textSecondary = colorScheme.TextSecondary,
        textTertiary = colorScheme.TextTertiary,
        focusRing = colorScheme.FocusRing,
        focusBackground = colorScheme.FocusBackground,
        rating = colorScheme.Rating
    )

    CompositionLocalProvider(
        LocalNuvioColors provides colorScheme,
        LocalNuvioExtendedColors provides extendedColors,
        LocalAppTheme provides appTheme,
        LocalC7ThemePackage provides customTheme
    ) {
        MaterialTheme(
            colorScheme = materialColorScheme,
            typography = buildNuvioTypography(getFontFamily(appFont)),
            content = content
        )
    }
}

object NuvioTheme {
    val colors: NuvioColorScheme
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioColors.current

    val extendedColors: NuvioExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalNuvioExtendedColors.current

    val packageTheme: ThemePackage?
        @Composable
        @ReadOnlyComposable
        get() = LocalC7ThemePackage.current

    val currentTheme: AppTheme
        @Composable
        @ReadOnlyComposable
        get() = LocalAppTheme.current
}

private fun Color.toArgbCompat(): Int = this.toArgb()
