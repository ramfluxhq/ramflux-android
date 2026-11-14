// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
@Composable
fun ChatNavigation(
    accountId: String,
    conversationId: String,
    onBack: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
    groupId: String? = null,
    onAttachments: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val attachments by rememberUpdatedState(onAttachments)
    BackHandler { vm.onAction(ChatAction.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(ChatAction.Refresh) }
    LaunchedEffect(vm, accountId, conversationId, groupId) { vm.onAction(ChatAction.Load(accountId, conversationId, groupId)) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.Back -> back()
                ChatEvent.Attachments -> attachments()
                ChatEvent.Sent -> vm.onAction(ChatAction.Refresh)
            }
        }
    }
    ChatScreen(state, vm::onAction)
}
