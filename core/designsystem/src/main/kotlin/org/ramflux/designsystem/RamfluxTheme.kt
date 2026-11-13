// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.generated.*

val LocalRFLayout = staticCompositionLocalOf { requireNotNull(ThemeCatalog.find("light")).layout }
val LocalRFTheme = staticCompositionLocalOf { RFDesign.light }

@Composable
fun RamfluxTheme(dark: Boolean = isSystemInDarkTheme(), themeId: String? = null, content: @Composable () -> Unit) {
    val named = ThemeCatalog.find(themeId) ?: ThemeCatalog.find(if (dark) "dark" else "light")
    val theme = named?.tokens ?: if (dark) RFDesign.dark else RFDesign.light
    val isDark = named?.dark ?: dark
    val configuration = LocalConfiguration.current
    val fontName = if (configuration.locales[0].language == "zh") theme.font else theme.font_latin
    val family = remember(fontName) { FontFamily(Typeface.create(fontName, Typeface.NORMAL)) }
    fun style(size: Float) = TextStyle(fontFamily = family, fontSize = size.sp)
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = theme.primary, onPrimary = theme.on_primary, primaryContainer = theme.selected,
        onPrimaryContainer = theme.text, inversePrimary = theme.on_primary,
        secondary = theme.secondary, onSecondary = theme.bg, secondaryContainer = theme.surface,
        onSecondaryContainer = theme.text, tertiary = theme.accent, onTertiary = theme.on_accent,
        tertiaryContainer = theme.selected, onTertiaryContainer = theme.text,
        background = theme.bg, onBackground = theme.text, surface = theme.surface, onSurface = theme.text,
        surfaceVariant = theme.surface_white, onSurfaceVariant = theme.secondary,
        surfaceTint = theme.primary, inverseSurface = theme.primary, inverseOnSurface = theme.on_primary,
        error = theme.danger, onError = theme.on_danger, errorContainer = theme.danger_bg, onErrorContainer = theme.danger,
        outline = theme.line_strong, outlineVariant = theme.line, scrim = theme.scrim,
        surfaceBright = theme.surface_white, surfaceDim = theme.surface, surfaceContainer = theme.surface,
        surfaceContainerLow = theme.bg, surfaceContainerLowest = theme.bg,
        surfaceContainerHigh = theme.surface_white, surfaceContainerHighest = theme.surface_white,
        primaryFixed = theme.primary, primaryFixedDim = theme.primary,
        onPrimaryFixed = theme.on_primary, onPrimaryFixedVariant = theme.on_primary,
        secondaryFixed = theme.surface, secondaryFixedDim = theme.surface,
        onSecondaryFixed = theme.text, onSecondaryFixedVariant = theme.text,
        tertiaryFixed = theme.accent, tertiaryFixedDim = theme.accent,
        onTertiaryFixed = theme.on_accent, onTertiaryFixedVariant = theme.on_accent
    )
    CompositionLocalProvider(LocalRFTheme provides theme, LocalRFLayout provides requireNotNull(named).layout) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(theme.radius_xs.dp),
                small = RoundedCornerShape(theme.radius_sm.dp),
                medium = RoundedCornerShape(theme.radius_md.dp),
                large = RoundedCornerShape(theme.radius_lg.dp),
                extraLarge = RoundedCornerShape(theme.radius_xl.dp)
            ),
            typography = Typography(
                displayLarge = style(theme.size_metric), displayMedium = style(theme.size_metric), displaySmall = style(theme.size_page),
                headlineLarge = style(
                    theme.size_mobile_large_title
                ),
                headlineMedium = style(theme.size_page), headlineSmall = style(theme.size_title),
                titleLarge = style(theme.size_title), titleMedium = style(theme.size_subheading), titleSmall = style(theme.size_secondary),
                bodyLarge = style(theme.size_mobile_body), bodyMedium = style(theme.size_body), bodySmall = style(theme.size_caption),
                labelLarge = style(theme.size_button), labelMedium = style(theme.size_meta), labelSmall = style(theme.size_mobile_caption)
            ),
            content = content
        )
    }
}
