// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.generated.*

@Composable
fun ChatScreen(state: ChatState, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFChatHeader(
                    "chat-header",
                    state.conversationId.ifEmpty { "Conversation" },
                    "",
                    theme,
                    leading = { TextButton(onClick = { onAction(ChatAction.Back) }) { Text("Back") } },
                    trailing = {
                        TextButton(
                            enabled = !state.loading && !state.sending,
                            onClick = { onAction(ChatAction.Refresh) }
                        ) { Text("Refresh") }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.loading) {
                        item(
                            "loading"
                        ) { RFStateBlock("Loading messages", "Reading private data…", RFStateKind.Loading, theme, action = {}) }
                    }
                    state.problem?.let { problem ->
                        item(
                            "error"
                        ) { RFStateBlock("Unable to continue", problem, RFStateKind.Error, theme, action = {}) }
                    }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") {
                            RFStateBlock("No messages", "This conversation has no messages.", RFStateKind.Empty, theme, action = {})
                        }
                    }
                    items(state.rows, key = { "message:" + it.id }, contentType = { if (it.deleted) "deleted" else "message" }) { row ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = if (row.own) Alignment.CenterEnd else Alignment.CenterStart) {
                            RFMessageBubble(
                                row.id,
                                if (row.own) RFBubbleKind.Own else RFBubbleKind.Peer,
                                row.sender,
                                theme,
                                content = { Text(row.content) },
                                quote = {},
                                attachment = {},
                                footer = {}
                            )
                        }
                    }
                }
                state.unavailable?.let { Text(it, Modifier.padding(16.dp)) }
                RFComposerShell("composer", theme, mode = {}, quote = {}, attachment = {}, editor = {
                    OutlinedTextField(
                        state.draft,
                        onValueChange = { onAction(ChatAction.Draft(it)) },
                        label = { Text("Message") },
                        enabled = !state.sending,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }, actions = {
                    TextButton(
                        enabled = state.canSend && !state.sending && state.draft.isNotBlank(),
                        onClick = { onAction(ChatAction.Send) }
                    ) { Text("Send") }
                })
            }
        }
    }
}
