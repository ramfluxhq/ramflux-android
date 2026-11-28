// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.MlsGroupRole
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSearchField
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.feature.mlsgroups.impl.R

private const val PREFETCH = 20

/** One entry of the flat list: a role header or a member row. */
private sealed interface Entry {
    val key: String

    data class Header(val role: MlsGroupRole) : Entry {
        override val key = "header:$role"
    }

    data class Member(val row: MlsMemberUi) : Entry {
        override val key = "member:" + row.anchor
    }
}

private fun entries(rows: List<MlsMemberUi>): List<Entry> {
    val owners = rows.filter { it.role == MlsGroupRole.OWNER }
    val admins = rows.filter { it.role == MlsGroupRole.ADMIN }
    val members = rows.filter { it.role != MlsGroupRole.OWNER && it.role != MlsGroupRole.ADMIN }
    return buildList {
        if (owners.isNotEmpty()) add(Entry.Header(MlsGroupRole.OWNER)).also { addAll(owners.map(Entry::Member)) }
        if (admins.isNotEmpty()) add(Entry.Header(MlsGroupRole.ADMIN)).also { addAll(admins.map(Entry::Member)) }
        if (members.isNotEmpty()) add(Entry.Header(MlsGroupRole.MEMBER)).also { addAll(members.map(Entry::Member)) }
    }
}

/**
 * The member list: virtualized, role headers over the loaded rows, the AI marker from the SDK flag, "You", "Member" for a
 * missing name (never the anchor), the device count, search over the loaded rows and the error row with "Try again".
 */
@Composable
fun MlsMembersScreen(state: MlsMembersUi, onAction: (MlsMembersAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val listed = remember(state.rows) { entries(state.rows) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - PREFETCH
        }
    }
    LaunchedEffect(nearEnd, state.rows.size, state.loading) {
        // A failed page loads again only on the explicit "Try again" tap.
        if (nearEnd && state.hasMore && !state.loading && state.failure == null && state.rows.isNotEmpty()) {
            onAction(
                MlsMembersAction.LoadMore
            )
        }
    }
    val indexOf = remember(state.rows) { state.rows.mapIndexed { index, row -> row.anchor to index }.toMap() }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            KitPageHeader(
                stringResource(R.string.group_members_title),
                onBack = { onAction(MlsMembersAction.Back) },
                tag = "mls-members-header"
            )
            KitSearchField(
                state.query,
                { onAction(MlsMembersAction.Search(it)) },
                stringResource(R.string.group_members_search),
                Modifier.padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp),
                tag = "mls-members-search"
            )
            state.actionFailure?.let {
                KitMeta(
                    failureText(it),
                    Modifier.padding(horizontal = theme.gap.dp),
                    tone = theme.danger,
                    tag = "mls-member-action-error"
                )
            }
            state.searchProgress?.let { (loaded, total) ->
                KitMeta(
                    stringResource(R.string.group_members_loading_all, loaded, total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                    Modifier.padding(horizontal = theme.gap.dp),
                    tag = "mls-members-progress"
                )
            }
            when {
                !state.loaded && state.loading -> KitMeta(
                    stringResource(R.string.group_members_loading),
                    Modifier.fillMaxWidth().padding(theme.gap.dp),
                    centered = true
                )
                !state.loaded && state.failure != null -> ErrorRow(state, onAction)
                state.loaded && state.rows.isEmpty() && state.query.isNotBlank() -> KitMeta(
                    stringResource(R.string.group_members_no_results),
                    Modifier.fillMaxWidth().padding(theme.gap.dp),
                    centered = true,
                    tag = "mls-members-empty"
                )
                else -> LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("mls-members-list"), state = listState) {
                    items(listed, key = { it.key }, contentType = { if (it is Entry.Header) "header" else "member" }) { entry ->
                        when (entry) {
                            is Entry.Header -> KitMeta(
                                when (entry.role) {
                                    MlsGroupRole.OWNER -> stringResource(R.string.group_members_section_owner)
                                    MlsGroupRole.ADMIN -> stringResource(R.string.group_members_section_admins)
                                    else -> stringResource(R.string.group_members_section_members)
                                },
                                Modifier.padding(horizontal = theme.gap.dp, vertical = (theme.gap * HALF).dp)
                            )
                            is Entry.Member -> MemberRow(entry.row, indexOf[entry.row.anchor] ?: 0, onAction)
                        }
                    }
                    if (state.failure != null) item(key = "error") { ErrorRow(state, onAction) }
                    state.details?.let { details -> item(key = "details") { TechnicalDetails(details) } }
                }
            }
        }
        state.menuAnchor?.let { anchor ->
            state.rows.firstOrNull { it.anchor == anchor }?.let { MlsMemberMenu(it, state, onAction) }
        }
        state.confirm?.let { MlsMemberConfirm(it, state.groupName, onAction) }
    }
}

@Composable
private fun MemberRow(row: MlsMemberUi, index: Int, onAction: (MlsMembersAction) -> Unit) {
    val devices = row.devices.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    val deviceText = if (devices > 0) pluralStringResource(R.plurals.group_members_devices, devices, devices) else null
    val updating = if (row.updating) stringResource(R.string.group_member_action_updating) else null
    val value = dotted(
        listOfNotNull(if (row.me) stringResource(R.string.group_members_you) else null, updating ?: deviceText)
    ).ifEmpty { null }
    val canAct = row.actions.isNotEmpty() && !row.updating
    KitRow(
        label = row.display ?: stringResource(R.string.group_member_unnamed),
        onClick = { if (canAct) onAction(MlsMembersAction.OpenMenu(row.anchor)) },
        value = value,
        trailing = KitRowTrailing.None,
        peer = row.display != null,
        ai = row.ai,
        testTag = if (canAct) "mls-member-menu-$index" else "mls-member-row"
    )
}

/** The group evidence, collapsed at the bottom of the list: host node, size tier and the latest position, nothing else. */
@Composable
private fun TechnicalDetails(details: MlsDetailsUi) {
    val theme = LocalRFTheme.current
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = theme.gap.dp).testTag("mls-members-details")) {
        KitRow(
            stringResource(R.string.group_members_details),
            { open = !open },
            trailing = KitRowTrailing.None,
            value = null,
            divider = false,
            testTag = "mls-members-details-toggle"
        )
        if (open) {
            details.hostNode?.takeIf { it.isNotBlank() }?.let {
                DetailRow(stringResource(R.string.group_members_details_host), it.take(NODE_DISPLAY), "mls-members-details-host")
            }
            if (details.tier > 0) {
                DetailRow(stringResource(R.string.group_members_details_tier), details.tier.toString(), "mls-members-details-tier")
            }
            DetailRow(
                stringResource(R.string.group_members_details_position),
                details.latestPosition.toString(),
                "mls-members-details-position"
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, tag: String) {
    KitRow(label, {}, value = value, trailing = KitRowTrailing.None, divider = false, testTag = tag)
}

@Composable
private fun ErrorRow(state: MlsMembersUi, onAction: (MlsMembersAction) -> Unit) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxWidth().padding(theme.gap.dp).testTag("mls-members-error")) {
        KitTipBar(
            state.failure?.let { failureText(it) } ?: stringResource(R.string.group_members_page_error),
            "circle-alert",
            action = stringResource(R.string.group_members_retry),
            onAction = { onAction(MlsMembersAction.Retry) },
            tag = "mls-members-retry"
        )
    }
}

private const val HALF = .5f
private const val NODE_DISPLAY = 128
