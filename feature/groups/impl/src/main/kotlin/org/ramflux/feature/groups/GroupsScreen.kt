// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSearchField
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.feature.groups.impl.R

@Composable
fun GroupsScreen(state: GroupsState, onAction: (GroupsAction) -> Unit, rooted: Boolean = false, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val title = stringResource(
        when (state.page) {
            GroupsPage.LIST -> R.string.groups_title
            GroupsPage.CREATE -> R.string.groups_create
            GroupsPage.DETAIL -> R.string.groups_detail
            GroupsPage.MEMBERS -> R.string.groups_members
            GroupsPage.REQUESTS -> R.string.groups_requests
            GroupsPage.INVITATION -> R.string.groups_invitation
        }
    )
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                if (rooted && state.page == GroupsPage.LIST) {
                    KitPageHeader(title, large = true, subtitle = state.account, tag = "groups-header")
                } else if (rooted) {
                    KitPageHeader(title, onBack = { onAction(GroupsAction.Back) }, tag = "groups-header")
                } else {
                    val subtitle = state.account.orEmpty()
                    KitPageHeader(
                        title,
                        subtitle = subtitle.takeIf { it.isNotEmpty() },
                        onBack = { onAction(GroupsAction.Back) },
                        actionLabel = stringResource(R.string.groups_refresh),
                        onAction = { if (!state.loading && !state.busy) onAction(GroupsAction.Refresh) }
                    )
                    if (subtitle.isNotEmpty()) Text(subtitle, color = theme.secondary)
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            KitEmpty("loader", stringResource(R.string.groups_loading), "")
                        }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { KitEmpty("circle-alert", stringResource(R.string.groups_error), it.render()) }
                    }
                    state.notice?.let { item("notice") { Text(it.render()) } }
                    state.pending?.let { id -> item("pending") { Text(stringResource(R.string.groups_pending, id)) } }
                    if (state.accountRequired) {
                        item("account") {
                            if (rooted) {
                                KitEmpty(
                                    "user-round",
                                    stringResource(R.string.groups_account_needed),
                                    stringResource(R.string.groups_account_help),
                                    action = stringResource(R.string.groups_account_action),
                                    onAction = { onAction(GroupsAction.SetupAccount) },
                                    tag = "groups-account"
                                )
                            } else {
                                KitEmpty(
                                    "inbox",
                                    stringResource(R.string.groups_account_needed),
                                    stringResource(R.string.groups_account_help),
                                    action = stringResource(R.string.groups_account_action),
                                    onAction = { onAction(GroupsAction.SetupAccount) }
                                )
                            }
                        }
                    }
                    when (state.page) {
                        GroupsPage.LIST -> {
                            item("search") {
                                KitSearchField(
                                    state.query,
                                    { onAction(GroupsAction.Query(it)) },
                                    stringResource(R.string.groups_filter),
                                    tag = "group-search"
                                )
                            }
                            if (state.loaded && state.groups.isEmpty()) {
                                item("empty") {
                                    if (rooted) {
                                        KitEmpty(
                                            "users-round",
                                            stringResource(R.string.groups_empty),
                                            stringResource(R.string.groups_empty_help),
                                            tag = "groups-empty"
                                        )
                                    } else {
                                        KitEmpty(
                                            "inbox",
                                            stringResource(R.string.groups_empty),
                                            stringResource(R.string.groups_empty_help)
                                        )
                                    }
                                }
                            }
                            items(state.groups, key = { "group:" + it.id }) { row ->
                                KitRow(
                                    row.title,
                                    { onAction(GroupsAction.Page(GroupsPage.DETAIL, row.id)) },
                                    value = row.count.render().takeIf { it.isNotEmpty() },
                                    trailing = KitRowTrailing.None,
                                    peer = true,
                                    testTag = row.id
                                )
                            }
                            item("invitations-title") {
                                Row {
                                    Text(
                                        stringResource(R.string.groups_invitations),
                                        Modifier.weight(1f),
                                        color = theme.secondary
                                    )
                                }
                            }
                            items(state.invitations, key = { "invite:" + it.id }) { row ->
                                KitRow(
                                    row.title,
                                    { onAction(GroupsAction.Page(GroupsPage.INVITATION, row.id)) },
                                    value = row.expiry.render().takeIf { it.isNotEmpty() },
                                    trailing = KitRowTrailing.None,
                                    peer = true,
                                    testTag = row.id
                                )
                            }
                        }
                        GroupsPage.CREATE -> item("create") {
                            GroupName(state.name, onAction)
                            Text(stringResource(R.string.groups_creator_only))
                            TextButton(
                                enabled = !state.loading && !state.busy && state.pending == null && state.name.isNotBlank(),
                                onClick = {
                                    onAction(GroupsAction.Create)
                                }
                            ) { Text(stringResource(R.string.groups_review_create)) }
                        }
                        GroupsPage.DETAIL -> item("detail") {
                            UntrustedText(state.title ?: state.selected.orEmpty(), style = MaterialTheme.typography.titleLarge)
                            Text(state.selected.orEmpty())
                            Text(stringResource(R.string.groups_epoch, state.epoch.render()))
                            if (!state.authorityFresh) Text(stringResource(R.string.groups_last_confirmed))
                            TextButton(enabled = !state.loading && !state.busy, onClick = {
                                onAction(GroupsAction.OpenChat)
                            }) { Text(stringResource(R.string.groups_messages)) }
                            TextButton(
                                onClick = { onAction(GroupsAction.Page(GroupsPage.MEMBERS, state.selected)) }
                            ) { Text(stringResource(R.string.groups_members)) }
                            TextButton(onClick = {
                                onAction(GroupsAction.Page(GroupsPage.REQUESTS, state.selected))
                            }) { Text(stringResource(R.string.groups_requests)) }
                            if (state.canManage) {
                                GroupName(state.name, onAction)
                                TextButton(enabled = !state.busy && state.pending == null && state.name.isNotBlank(), onClick = {
                                    onAction(GroupsAction.Rename)
                                }) { Text(stringResource(R.string.groups_rename)) }
                            } else {
                                Text(stringResource(R.string.groups_permissions))
                            }
                            TextButton(
                                onClick = { onAction(GroupsAction.Unavailable) }
                            ) { Text(stringResource(R.string.groups_other_controls)) }
                        }
                        GroupsPage.MEMBERS -> {
                            if (!state.authorityFresh) item("stale") { Text(stringResource(R.string.groups_last_confirmed)) }
                            items(state.members, key = { "member:" + it.id }) { member ->
                                Column(verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
                                    Text(member.principal.ifEmpty { member.device }, style = MaterialTheme.typography.titleMedium)
                                    Text(member.device)
                                    member.root?.let { Text(it) }
                                    Text(member.role.render())
                                    if (!member.active) Text(stringResource(R.string.groups_inactive))
                                    if (member.manageable) {
                                        TextButton(enabled = !state.busy && state.pending == null, onClick = {
                                            onAction(GroupsAction.Role(member.id, true))
                                        }) { Text(stringResource(R.string.groups_promote)) }
                                        TextButton(enabled = !state.busy && state.pending == null, onClick = {
                                            onAction(GroupsAction.Role(member.id, false))
                                        }) { Text(stringResource(R.string.groups_demote)) }
                                        TextButton(enabled = !state.busy && state.pending == null, onClick = {
                                            onAction(GroupsAction.Remove(member.id))
                                        }) { Text(stringResource(R.string.groups_remove)) }
                                    }
                                }
                            }
                            if (state.canManage) {
                                item("add-title") {
                                    Row {
                                        Text(stringResource(R.string.groups_add), Modifier.weight(1f), color = theme.secondary)
                                    }
                                }
                                items(state.contacts, key = { "contact:" + it.id }) { contact ->
                                    TextButton(
                                        enabled = !state.busy && state.pending == null,
                                        onClick = { onAction(GroupsAction.Add(contact.id)) }
                                    ) { UntrustedText(contact.title) }
                                }
                            }
                        }
                        GroupsPage.REQUESTS -> {
                            if (state.loaded && state.requests.isEmpty() && state.problem == null) {
                                item("no-requests") {
                                    Text(stringResource(R.string.groups_no_requests))
                                }
                            }
                            items(state.requests, key = { it.id }) { request ->
                                Column {
                                    Text(request.id)
                                    Text(request.status.render())
                                    Text(request.expiry.render())
                                }
                            }
                        }
                        GroupsPage.INVITATION -> {
                            items(listOfNotNull(state.selectedInvitation), key = { it.id }) { invitation ->
                                Column {
                                    UntrustedText(invitation.title, style = MaterialTheme.typography.titleLarge)
                                    Text(invitation.id)
                                    Text(uiText(R.string.groups_creator, UiText.Untrusted(invitation.creator)).render())
                                    Text(uiText(R.string.groups_inviter, UiText.Untrusted(invitation.inviter)).render())
                                    Text(invitation.expiry.render())
                                    TextButton(enabled = !state.busy && state.pending == null, onClick = {
                                        onAction(GroupsAction.Invitation(true))
                                    }) { Text(stringResource(R.string.groups_accept)) }
                                    TextButton(enabled = !state.busy && state.pending == null, onClick = {
                                        onAction(GroupsAction.Invitation(false))
                                    }) { Text(stringResource(R.string.groups_decline)) }
                                }
                            }
                        }
                    }
                }
                if (state.page == GroupsPage.LIST) {
                    TextButton(onClick = {
                        onAction(GroupsAction.Page(GroupsPage.CREATE))
                    }) { Text(stringResource(R.string.groups_create)) }
                }
            }
        }
    }
    state.confirmation?.let { facts ->
        HostConfirmationDialog(onDismissRequest = { onAction(GroupsAction.Deny) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                Text(stringResource(R.string.groups_review), color = theme.text)
                Text(facts.operation.render())
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(facts.values.indices.toList()) { index -> Text(facts.values[index].render()) }
                }
                HostConfirmationActions(
                    facts,
                    stringResource(R.string.groups_deny),
                    stringResource(R.string.groups_confirm),
                    stringResource(R.string.groups_confirm_accessibility, facts.operation.render()),
                    enabled = !state.busy && !state.loading,
                    onDeny = { onAction(GroupsAction.Deny) },
                    onConfirm = { onAction(GroupsAction.Confirm) }
                )
            }
        }
    }
}

@Composable
private fun GroupName(value: String, onAction: (GroupsAction) -> Unit) {
    OutlinedTextField(value, onValueChange = {
        onAction(GroupsAction.Name(it))
    }, label = { Text(stringResource(R.string.groups_name)) }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
}
