// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun AgentsNavigation(onBack: () -> Unit, onSetupAccount: () -> Unit, vm: AgentsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    BackHandler { vm.onAction(AgentsAction.Back) }
    DisposableEffect(vm) {
        vm.onAction(AgentsAction.Visible(true))
        onDispose { vm.onAction(AgentsAction.Visible(false)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(AgentsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                AgentsEvent.Back -> back()
                AgentsEvent.SetupAccount -> setup()
            }
        }
    }
    AgentsScreen(state, vm::onAction)
}
