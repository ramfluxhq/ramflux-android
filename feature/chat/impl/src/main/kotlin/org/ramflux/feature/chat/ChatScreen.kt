// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitBubble
import org.ramflux.designsystem.kit.KitBubbleKind
import org.ramflux.designsystem.kit.KitChatHeader
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.render
import org.ramflux.feature.chat.impl.R

@Composable
fun ChatScreen(state: ChatState, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                KitChatHeader(
                    state.title?.render() ?: stringResource(R.string.ui_conversation),
                    state.conversationId.takeIf { it.isNotEmpty() },
                    {},
                    { onAction(ChatAction.Back) },
                    tag = "chat-header"
                )
                TextButton(
                    enabled = !state.loading && !state.sending,
                    onClick = { onAction(ChatAction.Refresh) }
                ) { Text(stringResource(R.string.ui_refresh)) }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy((theme.gap * .75f).dp)
                ) {
                    if (state.loading) {
                        item("loading") {
                            KitEmpty(
                                "loader",
                                stringResource(R.string.ui_loading_messages),
                                stringResource(R.string.ui_reading_private_data)
                            )
                        }
                    }
                    state.problem?.let { problem ->
                        item("error") {
                            KitEmpty("circle-alert", stringResource(R.string.ui_unable_to_continue), problem.render())
                        }
                    }
                    state.headerProblem?.let { problem -> item("header-problem") { Text(problem.render()) } }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.ui_no_messages),
                                stringResource(R.string.ui_this_conversation_has_no_messages)
                            )
                        }
                    }
                    items(state.rows, key = { "message:" + it.id }, contentType = { if (it is CallItem) "call" else "message" }) { row ->
                        when (row) {
                            is CallItem -> Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.ui_call_record_unavailable))
                                Text(row.time.render(), style = MaterialTheme.typography.labelSmall)
                            }
                            is MessageItem -> Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = if (row.own && state.groupId == null) {
                                    Alignment.End
                                } else {
                                    Alignment.Start
                                }
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().pointerInput(row.id) {
                                        detectTapGestures { onAction(ChatAction.Select(row.id)) }
                                    },
                                    contentAlignment = if (row.own && state.groupId == null) Alignment.CenterEnd else Alignment.CenterStart
                                ) {
                                    Column {
                                        row.replyPreview?.let { Text(it.render()) }
                                        KitBubble(
                                            row.content.render(),
                                            kind = if (row.own && state.groupId == null) {
                                                KitBubbleKind.Own
                                            } else {
                                                KitBubbleKind.Incoming
                                            },
                                            sender = row.sender,
                                            time = row.time.render(),
                                            edited = if (row.edited) stringResource(R.string.ui_edited) else null,
                                            tag = row.id
                                        )
                                        row.receipt?.let { Text(it.render(), style = MaterialTheme.typography.labelSmall) }
                                        row.reaction?.let { Text(it.render(), style = MaterialTheme.typography.labelSmall) }
                                    }
                                }
                                if (state.selectedId == row.id) MessageActions(row, theme.gap, onAction)
                            }
                        }
                    }
                }
                if (state.peerTyping) Text(stringResource(R.string.ui_peer_typing), Modifier.padding(horizontal = theme.gap.dp))
                state.typingProblem?.let { Text(it.render(), Modifier.padding(horizontal = theme.gap.dp)) }
                state.deliveryNotice?.let { Text(it.render(), Modifier.padding(horizontal = theme.gap.dp)) }
                state.unavailable?.let { Text(it.render(), Modifier.padding(theme.gap.dp)) }
                Column(Modifier.testTag("composer"), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedTextField(
                                state.draft,
                                onValueChange = { onAction(ChatAction.Draft(it)) },
                                label = { Text(stringResource(composerLabel(state.composer))) },
                                enabled = !state.sending,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4
                            )
                        }
                        if (state.composer != ComposerMode.SEND) {
                            TextButton(onClick = { onAction(ChatAction.CancelComposer) }) {
                                Text(stringResource(R.string.ui_cancel))
                            }
                        }
                        TextButton(
                            enabled = !state.loading && !state.sending,
                            onClick = { onAction(ChatAction.Attachments) }
                        ) { Text(stringResource(R.string.ui_attach)) }
                        TextButton(
                            enabled = state.canSend && !state.sending && state.draft.isNotBlank(),
                            onClick = { onAction(ChatAction.Send) }
                        ) { Text(stringResource(R.string.ui_send)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageActions(row: MessageItem, gap: Float, onAction: (ChatAction) -> Unit) {
    val editable = row.own && !row.deleted && !row.placeholder && !row.service && row.content is UiText.Data
    Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
            if (!row.deleted && !row.placeholder) {
                TextButton(onClick = { onAction(ChatAction.Reply) }) { Text(stringResource(R.string.ui_reply)) }
            }
            if (editable) TextButton(onClick = { onAction(ChatAction.Edit) }) { Text(stringResource(R.string.ui_edit)) }
            if (!row.deleted) TextButton(onClick = { onAction(ChatAction.React) }) { Text(stringResource(R.string.ui_react)) }
        }
        if (!row.deleted) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                TextButton(onClick = { onAction(ChatAction.DeleteForMe) }) { Text(stringResource(R.string.ui_delete_for_me)) }
                TextButton(onClick = { onAction(ChatAction.DeleteForEveryone) }) {
                    Text(stringResource(R.string.ui_delete_for_everyone))
                }
            }
        }
    }
}

private fun composerLabel(mode: ComposerMode) = when (mode) {
    ComposerMode.REPLY -> R.string.ui_replying
    ComposerMode.EDIT -> R.string.ui_editing
    ComposerMode.SEND -> R.string.ui_message
}
