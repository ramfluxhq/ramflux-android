// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.ramflux.generated.*

val LocalRFTheme = staticCompositionLocalOf { RFDesign.light }

@Composable
fun RamfluxTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val theme = if (dark) RFDesign.dark else RFDesign.light
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalRFTheme provides theme) {
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = theme.primary,
                onPrimary = theme.on_primary,
                background = theme.bg,
                onBackground = theme.text,
                surface = theme.surface,
                onSurface = theme.text,
                error = theme.danger,
                outline = theme.line
            ),
            typography = Typography(
                bodyLarge = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = theme.size_mobile_body.sp
                ),
                bodyMedium = TextStyle(
                    fontFamily = FontFamily.SansSerif,
                    fontSize = theme.size_body.sp
                )
            ),
            content = content
        )
    }
}
