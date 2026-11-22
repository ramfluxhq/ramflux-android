// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
    rooted: Boolean = false,
    onDetails: (() -> Unit)? = null,
    onCall: (() -> Unit)? = null
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val attachments by rememberUpdatedState(onAttachments)
    val details by rememberUpdatedState(onDetails)
    val call by rememberUpdatedState(onCall)
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = state.selectedId != null) { vm.onAction(ChatAction.Dismiss) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(ChatAction.Refresh) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onAction(ChatAction.Pause) }
    LaunchedEffect(vm, accountId, conversationId, groupId) { vm.onAction(ChatAction.Load(accountId, conversationId, groupId)) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                ChatEvent.Back -> back()
                ChatEvent.Attachments -> attachments()
                ChatEvent.Sent -> vm.onAction(ChatAction.Refresh)
                ChatEvent.Details -> details?.invoke()
                ChatEvent.Call -> call?.invoke()
                is ChatEvent.Copy -> clipboard.setText(AnnotatedString(event.text))
            }
        }
    }
    ChatScreen(state, vm::onAction, modifier, rooted, showDetails = onDetails != null, showCall = onCall != null)
}
