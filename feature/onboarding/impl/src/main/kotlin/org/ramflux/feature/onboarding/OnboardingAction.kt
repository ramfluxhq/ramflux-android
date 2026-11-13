// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
sealed interface OnboardingAction {
    data object Refresh : OnboardingAction
    data class Page(val page: OnboardingPage) : OnboardingAction
    data class Address(val value: String) : OnboardingAction
    data object Discover : OnboardingAction
    data class ChooseNode(val id: String) : OnboardingAction
    data object UseNode : OnboardingAction
    data object Confirm : OnboardingAction
    data class SelectAccount(val id: String) : OnboardingAction
    data object Back : OnboardingAction
}
