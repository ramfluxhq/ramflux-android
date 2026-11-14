// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun GroupsNavigation(
    onBack: () -> Unit,
    onSetupAccount: () -> Unit,
    onChat: (String, String, String) -> Unit,
    vm: GroupsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    val chat by rememberUpdatedState(onChat)
    BackHandler { vm.onAction(GroupsAction.Back) }
    DisposableEffect(vm) {
        vm.onAction(GroupsAction.Visible(true))
        onDispose { vm.onAction(GroupsAction.Visible(false)) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(GroupsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                GroupsEvent.Back -> back()
                GroupsEvent.SetupAccount -> setup()
                is GroupsEvent.OpenChat -> chat(it.account, it.conversation, it.group)
            }
        }
    }
    GroupsScreen(state, vm::onAction)
}
