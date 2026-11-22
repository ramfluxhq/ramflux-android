// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import org.ramflux.designsystem.kit.KitHeaderAction
import org.ramflux.designsystem.kit.KitIconAvatar
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitSearchField
import org.ramflux.designsystem.kit.KitSkeletonRow
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
    val firstLoad = state.loading && !state.loaded && state.rows.isEmpty()
    val failedFirst = !state.loaded && !state.loading && state.problem != null
    Surface(modifier.fillMaxSize(), color = theme.bg) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                ConversationsHeader(state, onAction, rooted)
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("conversations-list")) {
                    item("permission-banner", contentType = "banner") { permissionBanner() }
                    if (!state.accountRequired && !(state.loaded && state.rows.isEmpty() && (state.requests ?: 0) == 0)) {
                        item("search", contentType = "search") {
                            // MOB-B01: 16 dp gutters, 20 dp below the header, 12 dp above the first row.
                            KitSearchField(
                                "",
                                {},
                                stringResource(R.string.ui_search_conversations),
                                Modifier.padding(
                                    start = theme.gap.dp,
                                    end = theme.gap.dp,
                                    top = (theme.gap * SEARCH_TOP).dp,
                                    bottom = (theme.gap * SEARCH_BOTTOM).dp
                                ),
                                enabled = false,
                                tag = "conversation-search"
                            )
                        }
                    }
                    if (firstLoad) {
                        items(SKELETON_ROWS, key = { "skeleton:$it" }, contentType = { "skeleton" }) { index ->
                            KitSkeletonRow(index, tag = if (index == 0) "conversations-loading" else null)
                        }
                    }
                    state.problem?.let { problem ->
                        item("error", contentType = "notice") {
                            if (failedFirst) {
                                KitEmpty(
                                    "wifi-off",
                                    stringResource(R.string.ui_cannot_connect_right_now),
                                    problem.render(),
                                    action = stringResource(R.string.ui_retry),
                                    onAction = { onAction(ConversationsAction.Refresh) },
                                    tag = "conversations-error"
                                )
                            } else {
                                Text(problem.render(), Modifier.padding(theme.gap.dp), color = theme.secondary)
                            }
                        }
                    }
                    state.unavailable?.let { message ->
                        item(
                            "unavailable",
                            contentType = "notice"
                        ) { Text(message.render(), Modifier.padding(theme.gap.dp), color = theme.secondary) }
                    }
                    if (state.accountRequired) {
                        item("account-required", contentType = "notice") { ConversationsAccount(onAction, rooted) }
                    }
                    state.requests?.takeIf { it > 0 }?.let { count ->
                        item("requests", contentType = "requests") {
                            KitConversationRow(
                                stringResource(R.string.ui_message_requests),
                                stringResource(R.string.ui_messages_from_non_contacts),
                                "",
                                { KitIconAvatar("user-round-search", AVATAR) },
                                { onAction(ConversationsAction.OpenRequests) },
                                badge = count.toString(),
                                tag = "conversations-requests"
                            )
                        }
                    }
                    if (state.loaded && state.rows.isEmpty() && (state.requests ?: 0) == 0) {
                        item("empty", contentType = "notice") {
                            // MOB-B12 centres the empty state in the pane.
                            Box(Modifier.fillParentMaxHeight(EMPTY_HEIGHT), contentAlignment = Alignment.Center) {
                                ConversationsEmpty(onAction, rooted)
                            }
                        }
                    }
                    items(state.rows, key = { "conversation:" + it.id }, contentType = { "conversation" }) { row ->
                        if (rooted) {
                            KitConversationRow(
                                row.title,
                                row.summary.render(),
                                row.timestamp.render(),
                                {
                                    if (row.group) {
                                        KitIconAvatar("users", AVATAR)
                                    } else {
                                        KitAvatar(row.title.take(1), AVATAR, KitAvatarKind.Person)
                                    }
                                },
                                { onAction(ConversationsAction.Open(row.id)) },
                                muted = row.muted,
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
                        item("more", contentType = "more") {
                            if (state.autoLoad) {
                                // Reaching the end of the window loads the next SDK page.
                                LaunchedEffect(state.rows.size) { onAction(ConversationsAction.LoadMore) }
                            }
                            Column(Modifier.fillMaxWidth().padding(theme.gap.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.ui_sorted_within_loaded), color = theme.secondary)
                                TextButton(
                                    enabled = !state.loading,
                                    onClick = { onAction(ConversationsAction.LoadMore) },
                                    modifier = Modifier.testTag("conversations-more")
                                ) { Text(stringResource(R.string.ui_load_more)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SKELETON_ROWS = 5
private const val AVATAR = 52f
private const val EMPTY_HEIGHT = 0.9f
private const val SEARCH_TOP = 1.25f
private const val SEARCH_BOTTOM = 0.75f

@Composable
private fun ConversationsHeader(state: ConversationsState, onAction: (ConversationsAction) -> Unit, rooted: Boolean) {
    val theme = LocalRFTheme.current
    if (rooted) {
        val compose = stringResource(R.string.ui_new_message)
        KitPageHeader(
            stringResource(R.string.ui_messages),
            large = true,
            subtitle = state.accountLabel,
            actions = if (state.accountId != null) {
                listOf(
                    KitHeaderAction("square-pen", compose) {
                        onAction(ConversationsAction.Compose)
                    }
                )
            } else {
                emptyList()
            },
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
private fun ConversationsEmpty(onAction: (ConversationsAction) -> Unit, rooted: Boolean) {
    KitEmpty(
        if (rooted) "messages-square" else "inbox",
        stringResource(R.string.ui_no_conversations_yet),
        stringResource(R.string.ui_add_a_contact_to_start_a_conversation),
        action = stringResource(R.string.ui_add_contact),
        onAction = { onAction(ConversationsAction.Compose) },
        tag = "conversations-empty"
    )
}
