// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
sealed interface SettingsEvent {
    data object Back : SettingsEvent
    data object OpenAccount : SettingsEvent
    data object Permissions : SettingsEvent
    data object Connections : SettingsEvent
}
