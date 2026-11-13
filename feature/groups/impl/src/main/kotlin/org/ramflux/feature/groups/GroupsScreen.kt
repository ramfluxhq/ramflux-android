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
import androidx.compose.ui.window.Dialog
import org.ramflux.designsystem.*
import org.ramflux.feature.groups.impl.R
import org.ramflux.generated.*

@Composable
fun GroupsScreen(state: GroupsState, onAction: (GroupsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(
                        when (state.page) {
                            GroupsPage.LIST -> R.string.groups_title
                            GroupsPage.CREATE -> R.string.groups_create
                            GroupsPage.DETAIL -> R.string.groups_detail
                            GroupsPage.MEMBERS -> R.string.groups_members
                            GroupsPage.REQUESTS -> R.string.groups_requests
                            GroupsPage.INVITATION -> R.string.groups_invitation
                        }
                    ),
                    state.account.orEmpty(),
                    theme,
                    leading = { TextButton(onClick = { onAction(GroupsAction.Back) }) { Text(stringResource(R.string.groups_back)) } },
                    trailing = {
                        TextButton(enabled = !state.loading && !state.busy, onClick = {
                            onAction(GroupsAction.Refresh)
                        }) { Text(stringResource(R.string.groups_refresh)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            RFStateBlock(stringResource(R.string.groups_loading), "", RFStateKind.Loading, theme, action = {})
                        }
                    }
                    state.problem?.let {
                        item(
                            "problem"
                        ) { RFStateBlock(stringResource(R.string.groups_error), it.render(), RFStateKind.Error, theme, action = {}) }
                    }
                    state.notice?.let { item("notice") { Text(it.render()) } }
                    state.pending?.let { id -> item("pending") { Text(stringResource(R.string.groups_pending, id)) } }
                    if (state.accountRequired) {
                        item("account") {
                            RFStateBlock(
                                stringResource(
                                    R.string.groups_account_needed
                                ),
                                stringResource(R.string.groups_account_help),
                                RFStateKind.Empty,
                                theme,
                                action = {
                                    TextButton(
                                        onClick = { onAction(GroupsAction.SetupAccount) }
                                    ) { Text(stringResource(R.string.groups_account_action)) }
                                }
                            )
                        }
                    }
                    when (state.page) {
                        GroupsPage.LIST -> {
                            item("search") {
                                RFSearchField(
                                    "group-search",
                                    stringResource(R.string.groups_filter),
                                    state.query,
                                    theme,
                                    onValueChange = { onAction(GroupsAction.Query(it)) }
                                )
                            }
                            if (state.loaded && state.groups.isEmpty()) {
                                item("empty") {
                                    RFStateBlock(
                                        stringResource(R.string.groups_empty),
                                        stringResource(R.string.groups_empty_help),
                                        RFStateKind.Empty,
                                        theme,
                                        action = {}
                                    )
                                }
                            }
                            items(state.groups, key = { "group:" + it.id }) { row ->
                                RFListRow(
                                    row.id,
                                    row.title,
                                    row.count.render(),
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(GroupsAction.Page(GroupsPage.DETAIL, row.id)) }
                                )
                            }
                            item("invitations-title") { RFSectionHeader(stringResource(R.string.groups_invitations), theme, trailing = {}) }
                            items(state.invitations, key = { "invite:" + it.id }) { row ->
                                RFListRow(
                                    row.id,
                                    row.title,
                                    row.expiry.render(),
                                    theme,
                                    leading = {},
                                    trailing = {},
                                    onAction = { onAction(GroupsAction.Page(GroupsPage.INVITATION, row.id)) }
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
                            Text(state.title ?: state.selected.orEmpty(), style = MaterialTheme.typography.titleLarge)
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
                                item("add-title") { RFSectionHeader(stringResource(R.string.groups_add), theme, trailing = {}) }
                                items(state.contacts, key = { "contact:" + it.id }) { contact ->
                                    TextButton(
                                        enabled = !state.busy && state.pending == null,
                                        onClick = { onAction(GroupsAction.Add(contact.id)) }
                                    ) { Text(contact.title) }
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
                                    Text(invitation.title, style = MaterialTheme.typography.titleLarge)
                                    Text(invitation.id)
                                    Text(stringResource(R.string.groups_creator, invitation.creator))
                                    Text(stringResource(R.string.groups_inviter, invitation.inviter))
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
        Dialog(onDismissRequest = { onAction(GroupsAction.Deny) }) {
            RFDialogShell(stringResource(R.string.groups_review), facts.operation.render(), theme, content = {
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(facts.values.indices.toList()) { index -> Text(facts.values[index].render()) }
                }
            }, actions = {
                TextButton(onClick = { onAction(GroupsAction.Deny) }) { Text(stringResource(R.string.groups_deny)) }
                TextButton(onClick = { onAction(GroupsAction.Confirm) }) { Text(stringResource(R.string.groups_confirm)) }
            })
        }
    }
}

@Composable
private fun GroupName(value: String, onAction: (GroupsAction) -> Unit) {
    OutlinedTextField(value, onValueChange = {
        onAction(GroupsAction.Name(it))
    }, label = { Text(stringResource(R.string.groups_name)) }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
}
