// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
sealed interface SettingsAction {
    data class Language(val language: org.ramflux.data.AppLanguage) : SettingsAction
    data object ApplyLanguage : SettingsAction
    data object Refresh : SettingsAction
    data object Back : SettingsAction
    data object OpenAccount : SettingsAction
    data object Permissions : SettingsAction
    data object Delivery : SettingsAction
    data object MyCard : SettingsAction
    data object Connections : SettingsAction
    data object Backup : SettingsAction
    data object Storage : SettingsAction
    data class Page(val page: SettingsPage) : SettingsAction
    data class Theme(val id: String?) : SettingsAction

    /** Asks for confirmation; nothing is revoked until [ConfirmRevoke]. */
    data class Revoke(val id: String) : SettingsAction
    data object ConfirmRevoke : SettingsAction
    data object CancelRevoke : SettingsAction

    /** Explicit lock: only the account code opens the account again on this phone. */
    data object Lock : SettingsAction

    /** Asks for confirmation; nothing leaves the phone until [ConfirmAccountExit]. */
    data class AccountExit(val exit: SettingsAccountExit) : SettingsAction
    data object ConfirmAccountExit : SettingsAction
    data object CancelAccountExit : SettingsAction

    /** Linking a new device: this phone invites, compares, reviews and approves. */
    data object StartPairing : SettingsAction
    data class InvitationAsText(val text: Boolean) : SettingsAction
    data class ComparePairing(val match: Boolean) : SettingsAction
    data object ApprovePairing : SettingsAction
    data object ConfirmApprovePairing : SettingsAction
    data object DeclinePairing : SettingsAction
    data object CancelPairing : SettingsAction

    data class RequestPolicy(val policy: org.ramflux.data.ContactRequestPolicy) : SettingsAction

    /** Switches the app lock on or off for the active account; only the account code opens it again. */
    data class SetAppLock(val enabled: Boolean) : SettingsAction

    /** The time away after which the app lock applies (one of [APP_LOCK_TIMEOUTS]). */
    data class AppLockTimeout(val timeoutMs: Long) : SettingsAction

    data object OpenCodeChange : SettingsAction

    /** One typed digit (or deletion with null) of the current or the new account code. */
    data class CodeDigit(val next: Boolean, val digit: Char?) : SettingsAction
    data object SubmitCodeChange : SettingsAction
    data object CloseCodeChange : SettingsAction
}
