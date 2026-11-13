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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.render
import org.ramflux.feature.chat.impl.R
import org.ramflux.generated.*

@Composable
fun ChatScreen(state: ChatState, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFChatHeader(
                    "chat-header",
                    state.title?.render() ?: stringResource(R.string.ui_conversation),
                    state.conversationId,
                    theme,
                    leading = { TextButton(onClick = { onAction(ChatAction.Back) }) { Text(stringResource(R.string.ui_back)) } },
                    trailing = {
                        TextButton(
                            enabled = !state.loading && !state.sending,
                            onClick = { onAction(ChatAction.Refresh) }
                        ) { Text(stringResource(R.string.ui_refresh)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy((theme.gap * .75f).dp)
                ) {
                    if (state.loading) {
                        item(
                            "loading"
                        ) {
                            RFStateBlock(
                                stringResource(
                                    R.string.ui_loading_messages
                                ),
                                stringResource(R.string.ui_reading_private_data),
                                RFStateKind.Loading,
                                theme,
                                action = {
                                }
                            )
                        }
                    }
                    state.problem?.let { problem ->
                        item(
                            "error"
                        ) {
                            RFStateBlock(
                                stringResource(R.string.ui_unable_to_continue),
                                problem.render(),
                                RFStateKind.Error,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.headerProblem?.let { problem -> item("header-problem") { Text(problem.render()) } }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") {
                            RFStateBlock(
                                stringResource(
                                    R.string.ui_no_messages
                                ),
                                stringResource(R.string.ui_this_conversation_has_no_messages),
                                RFStateKind.Empty,
                                theme,
                                action = {
                                }
                            )
                        }
                    }
                    items(state.rows, key = { "message:" + it.id }, contentType = { if (it is CallItem) "call" else "message" }) { row ->
                        when (row) {
                            is CallItem -> Column(Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.ui_call_record_unavailable))
                                Text(row.time.render(), style = MaterialTheme.typography.labelSmall)
                            }
                            is MessageItem -> Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = if (row.own) Alignment.CenterEnd else Alignment.CenterStart
                            ) {
                                RFMessageBubble(
                                    row.id,
                                    if (row.own) RFBubbleKind.Own else RFBubbleKind.Peer,
                                    row.sender,
                                    theme,
                                    content = { Text(row.content.render()) },
                                    quote = {},
                                    attachment = {},
                                    footer = { Text(row.time.render(), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
                state.unavailable?.let { Text(it.render(), Modifier.padding(theme.gap.dp)) }
                RFComposerShell("composer", theme, mode = {}, quote = {}, attachment = {}, editor = {
                    OutlinedTextField(
                        state.draft,
                        onValueChange = { onAction(ChatAction.Draft(it)) },
                        label = { Text(stringResource(R.string.ui_message)) },
                        enabled = !state.sending,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }, actions = {
                    TextButton(
                        enabled = state.canSend && !state.sending && state.draft.isNotBlank(),
                        onClick = { onAction(ChatAction.Send) }
                    ) { Text(stringResource(R.string.ui_send)) }
                })
            }
        }
    }
}
