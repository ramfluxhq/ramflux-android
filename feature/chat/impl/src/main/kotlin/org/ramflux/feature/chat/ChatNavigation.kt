// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
/**
 * The chat destination. [windowInsets] defaults to the safe drawing insets (including the keyboard) until
 * the shell scene applies insets itself and passes `WindowInsets(0)`; [showBack] is false in a detail pane.
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
    windowInsets: WindowInsets = WindowInsets.safeDrawing,
    showBack: Boolean = true
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    val attachments by rememberUpdatedState(onAttachments)
    BackHandler { vm.onAction(if (state.selectedId != null) ChatAction.Dismiss else ChatAction.Back) }
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
    ChatScreen(state, vm::onAction, modifier.windowInsetsPadding(windowInsets), showBack)
}
