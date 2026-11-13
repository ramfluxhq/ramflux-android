// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.generated.ThemeTokenFiles
import org.ramflux.generated.RFTheme

@Immutable
data class NamedThemeTokens(
    val id: String,
    @param:StringRes val nameRes: Int,
    val dark: Boolean,
    val tokens: RFTheme,
    val latinFont: String,
    val defaultFont: String,
    val layout: ThemeLayoutTokens
)

/** Adding a validated token JSON file adds its tokens and localized picker name at build time. */
object ThemeCatalog {
    val themes: ImmutableList<NamedThemeTokens> = ThemeTokenFiles.themes.toPersistentList()
    fun find(id: String?): NamedThemeTokens? = themes.firstOrNull { it.id == id }
}

@Immutable
data class ThemeLayoutTokens(val rowHeight: Float, val avatarSize: Float, val avatarTextSize: Float, val hairline: Float)
