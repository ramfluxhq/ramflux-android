// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitConversationRow
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.feature.mlsgroups.impl.R

private const val PREFETCH = 10
private const val AVATAR = 40f

/**
 * The MLS groups list: loading, offline, error with retry, empty (New group, Join with a link), the pending-join rows and the
 * groups, with the next page read within 10 rows of the end. Names and previews are peer data and go through the Kit's
 * untrusted-text rules. Joining happens in the sheet over this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MlsGroupsScreen(state: MlsGroupsState, onAction: (MlsGroupsAction) -> Unit, links: MlsLinkActions, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(
                stringResource(R.string.group_list_title),
                onBack = { onAction(MlsGroupsAction.Back) },
                tag = "mls-groups-header"
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
                horizontalArrangement = Arrangement.spacedBy((theme.gap * HALF).dp)
            ) {
                Box(Modifier.weight(1f)) {
                    KitButton(
                        stringResource(R.string.group_list_create),
                        { onAction(MlsGroupsAction.NewGroup) },
                        Modifier.fillMaxWidth(),
                        kind = KitButtonKind.Secondary,
                        tag = "mls-group-create"
                    )
                }
                Box(Modifier.weight(1f)) {
                    KitButton(
                        stringResource(R.string.group_list_join),
                        { onAction(MlsGroupsAction.OpenJoin) },
                        Modifier.fillMaxWidth(),
                        kind = KitButtonKind.Secondary,
                        tag = "mls-join-open"
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = state.loading && state.rows.isNotEmpty(),
                onRefresh = { onAction(MlsGroupsAction.Refresh) },
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Body(state, onAction)
            }
        }
        state.join?.let { MlsJoinSheet(it, onAction, links) }
    }
}

@Composable
private fun Body(state: MlsGroupsState, onAction: (MlsGroupsAction) -> Unit) {
    val failure = state.failure
    when {
        state.rows.isEmpty() && state.pending.isEmpty() && !state.loaded && failure == null ->
            KitEmpty("loader-circle", stringResource(R.string.group_list_loading), "", Modifier.testTag("mls-group-loading"))
        state.rows.isEmpty() && failure != null -> {
            val offline = failure.code == "host_unreachable"
            val text = when {
                failure.unavailable -> stringResource(R.string.group_error_unavailable)
                offline -> stringResource(R.string.group_list_offline)
                else -> stringResource(R.string.group_list_error)
            }
            KitEmpty(
                if (offline) "cloud-off" else "circle-alert",
                text,
                "",
                Modifier.testTag("mls-group-error"),
                action = if (failure.unavailable) null else stringResource(R.string.group_list_retry),
                onAction = if (failure.unavailable) null else ({ onAction(MlsGroupsAction.Refresh) }),
                tag = "mls-group-retry"
            )
        }
        state.rows.isEmpty() && state.pending.isEmpty() && state.loaded -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            KitEmpty("users", stringResource(R.string.group_list_empty), "", Modifier.testTag("mls-group-empty"))
        }
        else -> GroupList(state, onAction)
    }
}

@Composable
private fun GroupList(state: MlsGroupsState, onAction: (MlsGroupsAction) -> Unit) {
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - PREFETCH
        }
    }
    LaunchedEffect(nearEnd, state.rows.size, state.loadingMore) {
        if (nearEnd && state.rows.isNotEmpty() && !state.loadingMore && !state.loading) onAction(MlsGroupsAction.LoadMore)
    }
    val theme = LocalRFTheme.current
    LazyColumn(Modifier.fillMaxSize().testTag("mls-group-list"), state = listState) {
        items(state.pending, key = { "pending:" + it.requestId }) {
            androidx.compose.foundation.layout.Column {
                KitTipBar(
                    stringResource(R.string.group_join_pending_row),
                    "clock-3",
                    Modifier.padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
                    tag = "mls-join-pending-row"
                )
                if (it.reason == org.ramflux.data.MlsGroupPendingReason.AWAITING_MEMBER_ONLINE) {
                    KitMeta(
                        stringResource(R.string.group_join_pending_online_note),
                        Modifier.padding(horizontal = theme.gap.dp),
                        tag = "mls-join-pending-online-note"
                    )
                }
            }
        }
        items(state.rows, key = { it.id }, contentType = { "group" }) { row ->
            val members = pluralStringResource(R.plurals.group_list_members, row.members.toCount(), row.members.toCount())
            val agents = if (row.agents > 0) {
                pluralStringResource(R.plurals.group_list_agents, row.agents.toCount(), row.agents.toCount())
            } else {
                null
            }
            val counts = dotted(listOfNotNull(members, agents))
            KitConversationRow(
                title = row.name,
                preview = row.snippet.orEmpty(),
                time = activityText(row.lastActivityAt),
                avatar = { KitAvatar("", AVATAR, KitAvatarKind.Channel) },
                onClick = { onAction(MlsGroupsAction.OpenGroup(row.id)) },
                sender = counts,
                badge = row.unread.takeIf { it > 0 }?.let { it.toString() },
                tag = "mls-group-row-" + row.id
            )
        }
        if (state.loadingMore) {
            item(key = "more") {
                KitMeta(stringResource(R.string.group_list_loading), Modifier.fillMaxWidth().padding(theme.gap.dp), centered = true)
            }
        }
    }
}

private fun Long.toCount(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private const val HALF = .5f
