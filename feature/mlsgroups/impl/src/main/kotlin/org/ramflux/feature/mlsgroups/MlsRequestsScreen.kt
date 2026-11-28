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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsRequestPhase
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitButtonSize
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.feature.mlsgroups.impl.R

private const val PREFETCH = 20

/**
 * The join requests of an approval group, oldest first (D-M3-1): virtualized, a row per request with the name (peer data,
 * "this member" when there is none), the AI marker, when it was asked and when it expires, Approve and Decline (each opens a
 * confirmation), the in-flight phase of a row, the first-page and later-page errors with "Try again", the empty state and the
 * approval-off notice. An expired row keeps its buttons disabled; nothing is called by the clock.
 */
@Composable
fun MlsRequestsScreen(state: MlsRequestsUi, onAction: (MlsRequestsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - PREFETCH
        }
    }
    LaunchedEffect(nearEnd, state.rows.size, state.loadingMore) {
        // A failed page loads again only on the explicit "Try again" tap.
        if (nearEnd && state.hasMore && !state.loadingMore && !state.loading && state.pageFailure == null && state.rows.isNotEmpty()) {
            onAction(MlsRequestsAction.LoadMore)
        }
    }
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(
                stringResource(R.string.group_requests_title),
                onBack = { onAction(MlsRequestsAction.Back) },
                tag = "mls-requests-header"
            )
            if (state.goneNotice) {
                KitTipBar(
                    stringResource(R.string.group_error_request_not_found),
                    "circle-alert",
                    Modifier.padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
                    action = stringResource(R.string.group_invite_done),
                    onAction = { onAction(MlsRequestsAction.DismissNotice) },
                    tag = "mls-requests-gone"
                )
            }
            if (state.busy) {
                KitMeta(
                    stringResource(R.string.group_member_action_busy),
                    Modifier.padding(horizontal = theme.gap.dp),
                    tag = "mls-requests-busy"
                )
            }
            if (state.paced) {
                KitMeta(
                    failureText(MlsFailure("paced", state.pacedUntil)),
                    Modifier.padding(horizontal = theme.gap.dp),
                    tone = theme.danger,
                    tag = "mls-requests-paced"
                )
            }
            when {
                state.approvalOff -> KitMeta(
                    stringResource(R.string.group_requests_approval_off),
                    Modifier.fillMaxWidth().padding(theme.gap.dp),
                    centered = true,
                    tag = "mls-requests-approval-off"
                )
                state.loadFailure != null && state.rows.isEmpty() -> ErrorBar(
                    stringResource(R.string.group_requests_load_error),
                    { onAction(MlsRequestsAction.Retry) }
                )
                !state.loaded && state.rows.isEmpty() -> KitMeta(
                    stringResource(R.string.group_members_loading),
                    Modifier.fillMaxWidth().padding(theme.gap.dp),
                    centered = true,
                    tag = "mls-requests-loading"
                )
                state.loaded && state.rows.isEmpty() -> KitMeta(
                    stringResource(R.string.group_requests_empty),
                    Modifier.fillMaxWidth().padding(theme.gap.dp),
                    centered = true,
                    tag = "mls-requests-empty"
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mls-requests-list"), state = listState) {
                    itemsIndexed(state.rows, key = { _, row -> row.requestId }) { index, row ->
                        RequestRow(index, row, state, onAction)
                    }
                    if (state.pageFailure != null) {
                        item(key = "page-error") {
                            ErrorBar(stringResource(R.string.group_requests_page_error), { onAction(MlsRequestsAction.Retry) })
                        }
                    }
                }
            }
        }
        state.confirm?.let { ask ->
            val spec = when (ask.kind) {
                MlsRequestConfirmKind.APPROVE -> approveSpec(ask.display, ask.ai, state.groupName)
                MlsRequestConfirmKind.DECLINE -> declineSpec(
                    ask.display,
                    ask.ai,
                    state.groupName,
                    ask.expiresAt?.let { timeText(it) } ?: ""
                )
            }
            MlsAdminConfirm(spec, "mls-requests-confirm", { onAction(MlsRequestsAction.Confirm) }, { onAction(MlsRequestsAction.Cancel) })
        }
    }
}

@Composable
private fun RequestRow(index: Int, row: MlsRequestRowUi, state: MlsRequestsUi, onAction: (MlsRequestsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val asked = row.requestedAt?.let { stringResource(R.string.group_requests_requested, timeText(it)) }
    val expires = when {
        row.expired -> stringResource(R.string.group_requests_expired)
        row.expiresAt != null -> stringResource(R.string.group_requests_expires, timeText(row.expiresAt))
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        KitRow(
            label = row.display ?: stringResource(R.string.group_member_this),
            onClick = {},
            value = dotted(listOfNotNull(asked, expires)).ifEmpty { null },
            trailing = KitRowTrailing.None,
            peer = row.display != null,
            ai = row.ai,
            divider = false,
            testTag = "mls-request-row-$index"
        )
        val phaseText = when (row.phase) {
            MlsRequestPhase.APPROVING -> stringResource(R.string.group_requests_approving)
            MlsRequestPhase.ADDING -> stringResource(R.string.group_requests_adding)
            MlsRequestPhase.DECLINING -> stringResource(R.string.group_requests_declining)
            MlsRequestPhase.IDLE -> null
        }
        if (phaseText != null) {
            KitMeta(phaseText, Modifier.padding(horizontal = theme.gap.dp), tag = "mls-request-phase-$index")
        } else {
            Row(Modifier.padding(horizontal = theme.gap.dp), horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitButton(
                    stringResource(R.string.group_requests_approve),
                    { onAction(MlsRequestsAction.AskApprove(row.requestId)) },
                    kind = KitButtonKind.Primary,
                    size = KitButtonSize.Compact,
                    enabled = !row.expired && !state.busy && !state.paced,
                    tag = "mls-request-approve-$index"
                )
                KitButton(
                    stringResource(R.string.group_requests_decline),
                    { onAction(MlsRequestsAction.AskDecline(row.requestId)) },
                    kind = KitButtonKind.Secondary,
                    size = KitButtonSize.Compact,
                    enabled = !row.expired,
                    tag = "mls-request-decline-$index"
                )
            }
        }
        row.failure?.let {
            KitMeta(failureText(it), Modifier.padding(horizontal = theme.gap.dp), tone = theme.danger, tag = "mls-request-error-$index")
        }
    }
}

@Composable
private fun ErrorBar(text: String, onRetry: () -> Unit) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxWidth().padding(theme.gap.dp)) {
        KitTipBar(
            text,
            "circle-alert",
            action = stringResource(R.string.group_requests_retry),
            onAction = onRetry,
            tag = "mls-requests-retry"
        )
    }
}

private const val HALF = .5f
