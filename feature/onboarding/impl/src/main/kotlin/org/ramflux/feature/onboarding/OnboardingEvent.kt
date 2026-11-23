// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
sealed interface OnboardingEvent {
    data class AccountCreated(val id: String) : OnboardingEvent
    data class AccountSelected(val id: String) : OnboardingEvent
    data object Back : OnboardingEvent

    /** Restore an account from a backup archive on this new install (no account yet). */
    data object RestoreBackup : OnboardingEvent
}
