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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.ramflux.designsystem.ExactConversationRow
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.render
import org.ramflux.feature.conversations.impl.R
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
                            stringResource(R.string.ui_conversations),
                            state.accountLabel ?: stringResource(R.string.ui_account_state_unavailable),
                            theme,
                            leading = {
                                TextButton(
                                    onClick = { onAction(ConversationsAction.Back) }
                                ) { Text(stringResource(R.string.ui_back)) }
                            },
                            trailing = {
                                TextButton(
                                    enabled = !state.loading,
                                    onClick = { onAction(ConversationsAction.Refresh) }
                                ) { Text(stringResource(R.string.ui_refresh)) }
                            }
                        )
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversations-list")) {
                    item("search") {
                        RFSearchField(
                            "conversation-search",
                            stringResource(R.string.ui_search_conversations),
                            "",
                            theme,
                            disabled = true,
                            onValueChange = {}
                        )
                    }
                    if (state.loading) {
                        item("loading") {
                            RFStateBlock(
                                stringResource(R.string.ui_loading_conversations),
                                stringResource(R.string.ui_reading_private_data),
                                RFStateKind.Loading,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.problem?.let { problem ->
                        item("error") {
                            RFStateBlock(
                                stringResource(R.string.ui_unable_to_continue),
                                problem.render(),
                                RFStateKind.Error,
                                theme,
                                action = {}
                            )
                        }
                    }
                    state.unavailable?.let { message -> item("unavailable") { Text(message.render(), Modifier.padding(theme.gap.dp)) } }
                    if (state.accountRequired) {
                        item("account-required") {
                            RFStateBlock(
                                stringResource(R.string.ui_open_an_account),
                                stringResource(R.string.ui_an_unlocked_active_account_is_required),
                                RFStateKind.Empty,
                                theme,
                                action = {
                                    TextButton(
                                        onClick = { onAction(ConversationsAction.SetupAccount) }
                                    ) { Text(stringResource(R.string.ui_set_up_your_account)) }
                                }
                            )
                        }
                    }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") {
                            RFStateBlock(
                                stringResource(R.string.ui_no_conversations),
                                stringResource(R.string.ui_conversations_will_appear_here_when_available),
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
                            row.summary.render(),
                            row.timestamp.render(),
                            row.unread,
                            row.hasUnread,
                            onSelect = { onAction(ConversationsAction.Open(it)) }
                        )
                    }
                    if (state.hasMore) {
                        item("more") {
                            TextButton(
                                enabled = !state.loading,
                                onClick = { onAction(ConversationsAction.LoadMore) }
                            ) { Text(stringResource(R.string.ui_load_more)) }
                        }
                    }
                }
            }
        }
    }
}
