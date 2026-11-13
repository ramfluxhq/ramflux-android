// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.designsystem.ExactConversationRow
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.generated.*

@Composable
fun ConversationsScreen(state: ConversationsState, onAction: (ConversationsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                Box(Modifier.testTag("conversations-header")) {
                    MaterialTheme(
                        typography = MaterialTheme.typography.copy(
                            titleLarge =
                            MaterialTheme.typography.titleLarge.copy(fontSize = theme.size_mobile_large_title.sp)
                        )
                    ) {
                        RFPageHeader(
                            "Conversations",
                            state.accountLabel ?: "Account state unavailable",
                            theme,
                            leading = { TextButton(onClick = { onAction(ConversationsAction.Back) }) { Text("Back") } },
                            trailing = {
                                TextButton(
                                    enabled = !state.loading,
                                    onClick = { onAction(ConversationsAction.Refresh) }
                                ) { Text("Refresh") }
                            }
                        )
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversations-list")) {
                    item("search") {
                        RFSearchField(
                            "conversation-search",
                            "Search conversations",
                            "",
                            theme,
                            disabled = true,
                            onValueChange = {}
                        )
                    }
                    if (state.loading) {
                        item("loading") {
                            RFStateBlock(
                                "Loading conversations",
                                "Reading private data…",
                                RFStateKind.Loading,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.problem?.let { problem ->
                        item("error") {
                            RFStateBlock("Unable to continue", problem, RFStateKind.Error, theme, action = {})
                        }
                    }
                    state.unavailable?.let { message -> item("unavailable") { Text(message, Modifier.padding(16.dp)) } }
                    if (state.accountRequired) {
                        item("account-required") {
                            RFStateBlock(
                                "Open an account",
                                "An unlocked active account is required.",
                                RFStateKind.Empty,
                                theme,
                                action = {
                                    TextButton(
                                        onClick = { onAction(ConversationsAction.SetupAccount) }
                                    ) { Text("Set up your account") }
                                }
                            )
                        }
                    }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") {
                            RFStateBlock(
                                "No conversations",
                                "Conversations will appear here when available.",
                                RFStateKind.Empty,
                                theme,
                                action = {}
                            )
                        }
                    }
                    items(state.rows, key = { "conversation:" + it.id }, contentType = { "conversation" }) { row ->
                        ExactConversationRow(
                            row.id,
                            row.title,
                            row.summary,
                            row.timestamp,
                            row.unread,
                            row.hasUnread,
                            onSelect = { onAction(ConversationsAction.Open(it)) }
                        )
                    }
                    if (state.hasMore) {
                        item("more") {
                            TextButton(enabled = !state.loading, onClick = { onAction(ConversationsAction.LoadMore) }) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}
