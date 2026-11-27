// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
sealed interface SettingsEvent {
    data object Back : SettingsEvent
    data object OpenAccount : SettingsEvent
    data object Permissions : SettingsEvent
    data object Delivery : SettingsEvent
    data object MyCard : SettingsEvent
    data object Connections : SettingsEvent
    data object Backup : SettingsEvent
    data class Storage(val accountId: String) : SettingsEvent

    /** The account whose only way back is recovery from its recovery phrase. */
    data class Recover(val accountId: String) : SettingsEvent
}
