// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.Appearance
import org.ramflux.data.ClientFeature
import org.ramflux.designsystem.ThemeCatalog
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.app_shell.impl.R

@Immutable
data class ShellDestination(val feature: ClientFeature, val title: UiText)
enum class ShellNoticeKind { ERROR, UNAVAILABLE }

@Immutable
data class ShellNotice(val title: UiText, val description: UiText, val kind: ShellNoticeKind = ShellNoticeKind.ERROR)

@Immutable
data class AppShellState(
    val status: UiText = uiText(R.string.ui_opening_private_data),
    val ready: Boolean = false,
    val loading: Boolean = true,
    val canRetry: Boolean = false,
    val error: ShellNotice? = null,
    val notice: ShellNotice? = null,
    val appearance: Appearance = Appearance.SYSTEM,
    val savingAppearance: Boolean = false,
    val namedTheme: String? = null,
    val themes: ImmutableList<ThemeChoice> = ThemeCatalog.themes.map { ThemeChoice(it.id, uiText(it.nameRes)) }.toPersistentList(),
    val onboarding: ShellDestination = ShellDestination(ClientFeature.ONBOARDING, uiText(R.string.ui_set_up_your_account)),
    val conversations: ShellDestination = ShellDestination(ClientFeature.CONVERSATIONS, uiText(R.string.ui_conversations)),
    val contacts: ShellDestination = ShellDestination(ClientFeature.CONTACTS, uiText(R.string.ui_people)),
    val settings: ShellDestination = ShellDestination(ClientFeature.SETTINGS, uiText(R.string.ui_settings))
)

@Immutable data class ThemeChoice(val id: String, val label: UiText)
