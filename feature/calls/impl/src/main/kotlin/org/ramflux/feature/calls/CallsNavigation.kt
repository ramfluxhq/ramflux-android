// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun CallsNavigation(onBack: () -> Unit, onSetupAccount: () -> Unit, vm: CallsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    BackHandler { vm.onAction(CallsAction.Back) }
    DisposableEffect(vm) {
        vm.onAction(CallsAction.Visible(true))
        onDispose { vm.onAction(CallsAction.Visible(false)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(CallsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                CallsEvent.Back -> back()
                CallsEvent.SetupAccount -> setup()
            }
        }
    }
    CallsScreen(state, vm::onAction)
}
