// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
sealed interface OnboardingEvent {
    data class AccountSelected(val id: String) : OnboardingEvent
    data object Back : OnboardingEvent
}
