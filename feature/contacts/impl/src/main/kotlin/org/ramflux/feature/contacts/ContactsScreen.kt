// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ramflux.data.ContactOperation
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R
import org.ramflux.generated.*

@Composable
fun ContactsScreen(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().widthIn(max = theme.column_max.dp)) {
                RFPageHeader(
                    stringResource(
                        when (state.page) {
                            ContactsPage.LIST -> R.string.contacts_title
                            ContactsPage.DETAIL -> R.string.contacts_profile
                            ContactsPage.ADD -> R.string.contacts_add
                            ContactsPage.SAFETY -> R.string.contacts_safety
                            ContactsPage.DECLINE -> R.string.contacts_decline
                            ContactsPage.BATCH -> R.string.contacts_batch
                        }
                    ),
                    state.accountLabel.orEmpty(),
                    theme,
                    leading = { TextButton(onClick = { onAction(ContactsAction.Back) }) { Text(stringResource(R.string.contacts_back)) } },
                    trailing = {
                        TextButton(
                            enabled = !state.loading,
                            onClick = { onAction(ContactsAction.Refresh) }
                        ) { Text(stringResource(R.string.contacts_refresh)) }
                    }
                )
                if (state.page == ContactsPage.LIST) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp)) {
                        for (tab in ContactsTab.entries) {
                            RFChip(
                                "tab:" + tab.name,
                                stringResource(
                                    when (tab) {
                                        ContactsTab.CONTACTS -> R.string.contacts_tab_contacts
                                        ContactsTab.STRANGERS -> R.string.contacts_tab_strangers
                                        ContactsTab.SENT -> R.string.contacts_tab_sent
                                    }
                                ),
                                state.tab == tab,
                                theme,
                                onAction = { onAction(ContactsAction.Tab(tab)) }
                            )
                        }
                    }
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading) {
                        item("loading") {
                            RFStateBlock(
                                stringResource(
                                    R.string.contacts_loading
                                ),
                                stringResource(R.string.contacts_reading),
                                RFStateKind.Loading,
                                theme,
                                action = {
                                }
                            )
                        }
                    }
                    state.problem?.let { message ->
                        item("error") {
                            RFStateBlock(stringResource(R.string.contacts_error), message.render(), RFStateKind.Error, theme, action = {})
                        }
                    }
                    state.operationNotice?.let { notice -> item("operation-notice") { Text(notice.render()) } }
                    items(state.uncertain.indices.toList(), key = { "uncertain:" + it }) { index -> Text(state.uncertain[index].render()) }
                    state.unavailable?.let { message ->
                        item("unavailable") {
                            RFStateBlock(
                                stringResource(R.string.contacts_unavailable),
                                message.render(),
                                RFStateKind.Empty,
                                theme,
                                action = {}
                            )
                        }
                    }
                    if (state.accountRequired) {
                        item("account") {
                            RFStateBlock(
                                stringResource(
                                    R.string.contacts_account_required
                                ),
                                stringResource(R.string.contacts_account_explanation),
                                RFStateKind.Empty,
                                theme,
                                action = {
                                    TextButton(
                                        onClick = { onAction(ContactsAction.SetupAccount) }
                                    ) { Text(stringResource(R.string.contacts_account_action)) }
                                }
                            )
                        }
                    }
                    when (state.page) {
                        ContactsPage.LIST -> {
                            item("search") {
                                RFSearchField(
                                    "contacts-search",
                                    stringResource(
                                        R.string.contacts_search
                                    ),
                                    state.query,
                                    theme,
                                    onValueChange = {
                                        onAction(ContactsAction.Query(it))
                                    }
                                )
                            }
                            val rows = state.rows
                            if (state.loaded && rows.isEmpty()) {
                                item("empty") {
                                    RFStateBlock(
                                        stringResource(
                                            when {
                                                state.query.isNotBlank() -> R.string.contacts_no_matches
                                                state.tab == ContactsTab.CONTACTS -> R.string.contacts_empty
                                                else -> R.string.contacts_requests_empty
                                            }
                                        ),
                                        stringResource(R.string.contacts_empty_explanation),
                                        RFStateKind.Empty,
                                        theme,
                                        action = {}
                                    )
                                }
                            }
                            items(rows, key = { "person:" + it.id }, contentType = { "identity" }) { row ->
                                RFListRow(
                                    row.id,
                                    row.label,
                                    row.principal,
                                    theme,
                                    leading = { RFAvatar(row.label.take(1), RFAvatarKind.Person, theme) },
                                    trailing = { Text(row.status.render()) },
                                    onAction = { onAction(ContactsAction.Select(row.id)) }
                                )
                            }
                        }
                        ContactsPage.DETAIL, ContactsPage.SAFETY, ContactsPage.DECLINE -> state.selected?.let { row ->
                            item("identity") {
                                RFListRow(
                                    row.id,
                                    row.label,
                                    row.principal,
                                    theme,
                                    leading = { RFAvatar(row.label.take(1), RFAvatarKind.Person, theme) },
                                    trailing = {},
                                    onAction = {}
                                )
                                Text(row.status.render())
                                row.note?.let { Text(it) }
                                row.count?.let { Text(it.render()) }
                                row.expires?.let { Text(stringResource(R.string.contacts_expires, it.render())) }
                            }
                            when (state.page) {
                                ContactsPage.SAFETY -> item("safety") {
                                    RFTrustBanner(
                                        stringResource(
                                            R.string.contacts_safety
                                        ),
                                        stringResource(R.string.contacts_safety_explanation),
                                        false,
                                        theme,
                                        icon = {
                                        },
                                        action = {}
                                    )
                                    OperationButton(R.string.contacts_verify, ContactOperation.VERIFY, state.loading, onAction)
                                    OperationButton(R.string.contacts_scan, ContactOperation.SCAN, state.loading, onAction)
                                }
                                ContactsPage.DECLINE -> item("decline") {
                                    Text(stringResource(R.string.contacts_decline_explanation))
                                    OperationButton(R.string.contacts_decline, ContactOperation.DECLINE, state.loading, onAction)
                                    OperationButton(R.string.contacts_block, ContactOperation.BLOCK, state.loading, onAction)
                                    OperationButton(R.string.contacts_report, ContactOperation.REPORT, state.loading, onAction)
                                }
                                else -> item("details") {
                                    TextButton(
                                        onClick = { onAction(ContactsAction.Page(ContactsPage.SAFETY)) }
                                    ) { Text(stringResource(R.string.contacts_safety)) }
                                    if (row.inbound) Text(stringResource(R.string.contacts_stranger_explanation))
                                    if (row.request && !row.inbound) Text(stringResource(R.string.contacts_sent_explanation))
                                }
                            }
                        }
                        ContactsPage.ADD -> item("add") {
                            OutlinedTextField(
                                state.address,
                                onValueChange = { onAction(ContactsAction.Address(it)) },
                                label = { Text(stringResource(R.string.contacts_address)) },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )
                            Text(stringResource(R.string.contacts_add_explanation))
                            OperationButton(
                                R.string.contacts_send_request,
                                ContactOperation.ADD,
                                state.loading || state.address.isBlank(),
                                onAction
                            )
                        }
                        ContactsPage.BATCH -> {
                            item("batch-instructions") { Text(stringResource(R.string.contacts_batch_explanation)) }
                            items(state.rows, key = { "select:" + it.id }) { row ->
                                Row(
                                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}
                                        .toggleable(
                                            row.id in state.selectedIds,
                                            role = Role.Checkbox,
                                            onValueChange = { onAction(ContactsAction.Toggle(row.id)) }
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(row.id in state.selectedIds, onCheckedChange = null)
                                    Text(row.label)
                                }
                            }
                            item("batch-action") {
                                OperationButton(
                                    R.string.contacts_decline_selected,
                                    ContactOperation.BATCH_DECLINE,
                                    state.loading || state.selectedIds.isEmpty(),
                                    onAction
                                )
                            }
                        }
                    }
                }
                if (state.page == ContactsPage.LIST) {
                    Row(Modifier.padding(theme.gap.dp)) {
                        TextButton(
                            onClick = { onAction(ContactsAction.Page(ContactsPage.ADD)) }
                        ) { Text(stringResource(R.string.contacts_add)) }
                        if (state.tab == ContactsTab.STRANGERS) {
                            TextButton(onClick = {
                                onAction(ContactsAction.Page(ContactsPage.BATCH))
                            }) { Text(stringResource(R.string.contacts_batch)) }
                        }
                    }
                }
                if (state.page == ContactsPage.DETAIL && state.selected?.inbound == true) {
                    Row(Modifier.fillMaxWidth().padding(theme.gap.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { onAction(ContactsAction.Page(ContactsPage.DECLINE)) }
                        ) { Text(stringResource(R.string.contacts_decline)) }
                        OperationButton(R.string.contacts_reply, ContactOperation.REPLY, state.loading, onAction)
                        OperationButton(R.string.contacts_accept, ContactOperation.ACCEPT, state.loading, onAction)
                    }
                }
            }
        }
    }
    state.approval?.let { approval ->
        Dialog(onDismissRequest = { onAction(ContactsAction.Deny) }) {
            RFDialogShell(stringResource(R.string.contacts_confirm), approval.operation.render(), theme, content = {
                Text(stringResource(R.string.contacts_confirm_account, approval.account))
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(approval.targets) { target -> Text(target) }
                }
                HoldConfirmation(stringResource(if (approval.held) R.string.contacts_release else R.string.contacts_hold), approval.held,
                    onStart = { onAction(ContactsAction.HoldStart) }, onEnd = { onAction(ContactsAction.HoldEnd(it)) })
            }, actions = {
                TextButton(onClick = { onAction(ContactsAction.Deny) }) { Text(stringResource(R.string.contacts_deny)) }
            })
        }
    }

}

@Composable
private fun OperationButton(label: Int, operation: ContactOperation, disabled: Boolean, onAction: (ContactsAction) -> Unit) {
    TextButton(enabled = !disabled, onClick = { onAction(ContactsAction.Operate(operation)) }) { Text(stringResource(label)) }
}
