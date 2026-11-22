// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
/**
 * The chat destination. The shell owns window insets (including the keyboard), the width cap and system
 * back: back pops the chat from the shell's stack, so predictive back animates through the shell. Only
 * the open message actions intercept back, to close themselves. [rooted] is true when the shell shows the
 * chat as the detail beside its list, where it has no back control.
 */
@Composable
fun ChatNavigation(
    accountId: String,
    conversationId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: ChatViewModel = hiltViewModel(),
    groupId: String? = null,
    onAttachments: () -> Unit = {},
    rooted: Boolean = false
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val attachments by rememberUpdatedState(onAttachments)
    BackHandler(enabled = state.selectedId != null) { vm.onAction(ChatAction.Dismiss) }
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
    ChatScreen(state, vm::onAction, modifier, rooted)
}
