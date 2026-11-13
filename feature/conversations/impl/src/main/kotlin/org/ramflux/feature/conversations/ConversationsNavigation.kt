// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun ConversationsNavigation(
    onBack: () -> Unit,
    onSetupAccount: () -> Unit,
    onOpenChat: (String, String) -> Unit,
    vm: ConversationsViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val setup by rememberUpdatedState(onSetupAccount)
    val chat by rememberUpdatedState(onOpenChat)
    BackHandler { vm.onAction(ConversationsAction.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(ConversationsAction.Refresh) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ConversationsEvent.Back -> back()
                ConversationsEvent.SetupAccount -> setup()
                is ConversationsEvent.OpenChat -> chat(event.accountId, event.conversationId)
            }
        }
    }
    ConversationsScreen(state, vm::onAction)
}
