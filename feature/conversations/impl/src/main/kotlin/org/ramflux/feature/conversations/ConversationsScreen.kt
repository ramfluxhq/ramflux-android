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
import org.ramflux.designsystem.ExactConversationRow
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitConversationRow
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitSearchField
import org.ramflux.designsystem.render
import org.ramflux.feature.conversations.impl.R

/**
 * Screen contract: fills the pane it is given ([modifier]); the shell owns window insets and the
 * width cap. [rooted] means the screen is a tab root or list pane and shows no back affordance.
 */
@Composable
fun ConversationsScreen(
    state: ConversationsState,
    onAction: (ConversationsAction) -> Unit,
    permissionBanner: @Composable () -> Unit = {},
    rooted: Boolean = false,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    Surface(modifier.fillMaxSize(), color = theme.bg) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                ConversationsHeader(state, onAction, rooted)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversations-list")) {
                    item("permission-banner") { permissionBanner() }
                    item("search") {
                        KitSearchField(
                            "",
                            {},
                            stringResource(R.string.ui_search_conversations),
                            enabled = false,
                            tag = "conversation-search"
                        )
                    }
                    if (state.loading) {
                        item("loading") {
                            KitEmpty(
                                "loader",
                                stringResource(R.string.ui_loading_conversations),
                                stringResource(R.string.ui_reading_private_data)
                            )
                        }
                    }
                    state.problem?.let { problem ->
                        item("error") {
                            KitEmpty(
                                "circle-alert",
                                stringResource(R.string.ui_unable_to_continue),
                                problem.render()
                            )
                        }
                    }
                    state.unavailable?.let { message -> item("unavailable") { Text(message.render(), Modifier.padding(theme.gap.dp)) } }
                    if (state.accountRequired) {
                        item("account-required") { ConversationsAccount(onAction, rooted) }
                    }
                    if (state.loaded && state.rows.isEmpty()) {
                        item("empty") { ConversationsEmpty(rooted) }
                    }
                    items(state.rows, key = { "conversation:" + it.id }, contentType = { "conversation" }) { row ->
                        if (rooted) {
                            KitConversationRow(
                                row.title,
                                row.summary.render(),
                                row.timestamp.render(),
                                { KitAvatar(row.title.take(1), 52f, KitAvatarKind.Person) },
                                { onAction(ConversationsAction.Open(row.id)) },
                                badge = row.unread.takeIf { row.hasUnread },
                                tag = "conversation-" + row.id
                            )
                        } else {
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

@Composable
private fun ConversationsHeader(state: ConversationsState, onAction: (ConversationsAction) -> Unit, rooted: Boolean) {
    val theme = LocalRFTheme.current
    if (rooted) {
        KitPageHeader(
            stringResource(R.string.ui_messages),
            large = true,
            subtitle = state.accountLabel,
            tag = "conversations-header"
        )
    } else {
        val subtitle = state.accountLabel ?: stringResource(R.string.ui_account_state_unavailable)
        Column {
            KitPageHeader(
                stringResource(R.string.ui_conversations),
                subtitle = subtitle.takeIf { it.isNotEmpty() },
                onBack = { onAction(ConversationsAction.Back) },
                actionLabel = stringResource(R.string.ui_refresh),
                onAction = { if (!state.loading) onAction(ConversationsAction.Refresh) },
                tag = "conversations-header"
            )
            if (subtitle.isNotEmpty()) Text(subtitle, color = theme.secondary)
        }
    }
}

@Composable
private fun ConversationsAccount(onAction: (ConversationsAction) -> Unit, rooted: Boolean) {
    if (rooted) {
        KitEmpty(
            "user-round",
            stringResource(R.string.ui_open_an_account),
            stringResource(R.string.ui_an_unlocked_active_account_is_required),
            action = stringResource(R.string.ui_set_up_your_account),
            onAction = { onAction(ConversationsAction.SetupAccount) },
            tag = "conversations-account"
        )
    } else {
        KitEmpty(
            "inbox",
            stringResource(R.string.ui_open_an_account),
            stringResource(R.string.ui_an_unlocked_active_account_is_required),
            action = stringResource(R.string.ui_set_up_your_account),
            onAction = { onAction(ConversationsAction.SetupAccount) }
        )
    }
}

@Composable
private fun ConversationsEmpty(rooted: Boolean) {
    if (rooted) {
        KitEmpty(
            "messages-square",
            stringResource(R.string.ui_no_conversations),
            stringResource(R.string.ui_conversations_will_appear_here_when_available),
            tag = "conversations-empty"
        )
    } else {
        KitEmpty(
            "inbox",
            stringResource(R.string.ui_no_conversations),
            stringResource(R.string.ui_conversations_will_appear_here_when_available)
        )
    }
}
