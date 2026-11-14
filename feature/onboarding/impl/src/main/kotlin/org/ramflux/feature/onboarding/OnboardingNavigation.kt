// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Host owns navigation callbacks; the feature never depends on another implementation. */
@Composable
fun OnboardingNavigation(
    onBack: () -> Unit,
    onAccountSelected: (String) -> Unit,
    onAccountCreated: (String) -> Unit = {},
    vm: OnboardingViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { vm.onAction(OnboardingAction.Back) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                OnboardingEvent.Back -> onBack()
                is OnboardingEvent.AccountSelected -> onAccountSelected(event.id)
                is OnboardingEvent.AccountCreated -> onAccountCreated(event.id)
            }
        }
    }
    OnboardingScreen(state, vm::onAction)
}
