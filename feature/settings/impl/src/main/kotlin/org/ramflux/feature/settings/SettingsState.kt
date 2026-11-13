// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.AppLanguage
import org.ramflux.designsystem.*

enum class SettingsPage { GENERAL, APPEARANCE, LANGUAGE, DEVICES, BACKUP }

@Immutable data class SettingsThemeChoice(val id: String, val label: UiText)

@Immutable data class SettingsDevice(val id: String, val local: Boolean)

@Immutable data class SettingsStatus(val sessions: UiText, val sync: UiText, val node: UiText, val lastRun: UiText)

@Immutable data class SettingsState(
    val page: SettingsPage = SettingsPage.GENERAL,
    val accountLabel: String? = null,
    val accountRequired: Boolean = false,
    val loading: Boolean = false,
    val selectedTheme: String? = null,
    val themes: ImmutableList<SettingsThemeChoice> = ThemeCatalog.themes.map {
        SettingsThemeChoice(it.id, uiText(it.nameRes))
    }.toPersistentList(),
    val savingTheme: Boolean = false,
    val selectedLanguage: AppLanguage = AppLanguage.SYSTEM,
    val savedLanguage: AppLanguage = AppLanguage.SYSTEM,
    val languageDraft: AppLanguage? = null,
    val savingLanguage: Boolean = false,
    val devices: ImmutableList<SettingsDevice> = persistentListOf(),
    val devicesLoaded: Boolean = false,
    val deviceProblem: UiText? = null,
    val status: SettingsStatus? = null,
    val statusProblem: UiText? = null,
    val problem: UiText? = null,
    val unavailable: UiText? = null
)
