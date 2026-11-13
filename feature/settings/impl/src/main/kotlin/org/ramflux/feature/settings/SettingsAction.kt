// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
sealed interface SettingsAction {
    data object Refresh : SettingsAction
    data object Back : SettingsAction
    data object OpenAccount : SettingsAction
    data object Connections : SettingsAction
    data class Page(val page: SettingsPage) : SettingsAction
    data class Theme(val id: String?) : SettingsAction
    data class Revoke(val id: String) : SettingsAction
}
