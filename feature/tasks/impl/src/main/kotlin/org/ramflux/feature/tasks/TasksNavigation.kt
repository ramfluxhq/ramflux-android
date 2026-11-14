// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun TasksNavigation(onBack: () -> Unit, onSetupAccount: () -> Unit, vm: TasksViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    BackHandler { vm.onAction(TasksAction.Back) }
    DisposableEffect(vm) {
        vm.onAction(TasksAction.Visible(true))
        onDispose { vm.onAction(TasksAction.Visible(false)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(TasksAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                TasksEvent.Back -> back()
                TasksEvent.SetupAccount -> setup()
            }
        }
    }
    TasksScreen(state, vm::onAction)
}
