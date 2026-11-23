// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun CallsNavigation(
    onBack: () -> Unit,
    onSetupAccount: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenCall: (account: String, call: String) -> Unit = { _, _ -> },
    vm: CallsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    val open by rememberUpdatedState(onOpenCall)
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
                is CallsEvent.OpenCall -> open(it.accountId, it.callId)
            }
        }
    }
    CallsScreen(state, vm::onAction, modifier = modifier)
}
