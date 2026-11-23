// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
sealed interface OnboardingAction {
    data object Refresh : OnboardingAction
    data class Page(val page: OnboardingPage) : OnboardingAction
    data class Address(val value: String) : OnboardingAction
    data class OperatorKey(val value: String) : OnboardingAction
    data object ReviewAddress : OnboardingAction
    data object Discover : OnboardingAction
    data class ChooseNode(val id: String) : OnboardingAction
    data object UseNode : OnboardingAction
    data object Confirm : OnboardingAction
    data class SelectAccount(val id: String) : OnboardingAction

    /** The handler reads this buffer (the six-digit account code) once and erases it. */
    class SubmitUnlock(val code: ErasableBuffer) : OnboardingAction
    data class DisplayName(val value: String) : OnboardingAction
    data object BeginCreate : OnboardingAction
    data object PhraseSaved : OnboardingAction

    /** The confirm handler owns these buffers: it reads them once and erases them after the call returns. */
    class ConfirmChallenge(val words: List<ErasableBuffer>, val code: ErasableBuffer) : OnboardingAction

    /** Starts phrase recovery of an account whose data is on this phone. */
    data class BeginRecover(val accountId: String) : OnboardingAction

    /** Removes an account the SDK refuses from this device's list of offered accounts. */
    data class ForgetAccount(val accountId: String) : OnboardingAction

    /** The handler owns both buffers: it reads them once and erases them after the call returns. */
    class ConfirmRecover(val phrase: ErasableBuffer, val code: ErasableBuffer) : OnboardingAction

    data object ContinueCreated : OnboardingAction
    data object Back : OnboardingAction
    data object RestoreBackup : OnboardingAction
}
