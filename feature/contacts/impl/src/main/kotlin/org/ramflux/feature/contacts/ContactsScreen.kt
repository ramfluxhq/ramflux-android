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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ramflux.data.ContactOperation
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.contacts.impl.R

@Composable
fun ContactsScreen(state: ContactsState, onAction: (ContactsAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val subtitle = state.accountLabel?.takeIf { it.isNotEmpty() }
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().widthIn(max = theme.column_max.dp)) {
                KitPageHeader(
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
                    subtitle = subtitle,
                    onBack = { onAction(ContactsAction.Back) },
                    actionLabel = stringResource(R.string.contacts_refresh),
                    onAction = { if (!state.loading) onAction(ContactsAction.Refresh) }
                )
                if (subtitle != null) Text(subtitle, color = theme.secondary)
                if (state.page == ContactsPage.LIST) {
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp)) {
                        for (tab in ContactsTab.entries) {
                            FilterChip(
                                selected = state.tab == tab,
                                onClick = { onAction(ContactsAction.Tab(tab)) },
                                label = {
                                    Text(
                                        stringResource(
                                            when (tab) {
                                                ContactsTab.CONTACTS -> R.string.contacts_tab_contacts
                                                ContactsTab.STRANGERS -> R.string.contacts_tab_strangers
                                                ContactsTab.SENT -> R.string.contacts_tab_sent
                                            }
                                        )
                                    )
                                },
                                modifier = Modifier.testTag("tab:" + tab.name)
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
                            KitEmpty(
                                "loader",
                                stringResource(R.string.contacts_loading),
                                stringResource(R.string.contacts_reading)
                            )
                        }
                    }
                    state.problem?.let { message ->
                        item("error") {
                            KitEmpty("circle-alert", stringResource(R.string.contacts_error), message.render())
                        }
                    }
                    state.operationNotice?.let { notice -> item("operation-notice") { Text(notice.render()) } }
                    items(state.uncertain.indices.toList(), key = { "uncertain:" + it }) { index -> Text(state.uncertain[index].render()) }
                    state.unavailable?.let { message ->
                        item("unavailable") {
                            KitEmpty("inbox", stringResource(R.string.contacts_unavailable), message.render())
                        }
                    }
                    if (state.accountRequired) {
                        item("account") {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.contacts_account_required),
                                stringResource(R.string.contacts_account_explanation),
                                action = stringResource(R.string.contacts_account_action),
                                onAction = { onAction(ContactsAction.SetupAccount) }
                            )
                        }
                    }
                    when (state.page) {
                        ContactsPage.LIST -> {
                            item("search") {
                                KitSearchField(
                                    state.query,
                                    { onAction(ContactsAction.Query(it)) },
                                    stringResource(R.string.contacts_search),
                                    tag = "contacts-search"
                                )
                            }
                            val rows = state.rows
                            if (state.loaded && rows.isEmpty()) {
                                item("empty") {
                                    KitEmpty(
                                        "inbox",
                                        stringResource(
                                            when {
                                                state.query.isNotBlank() -> R.string.contacts_no_matches
                                                state.tab == ContactsTab.CONTACTS -> R.string.contacts_empty
                                                else -> R.string.contacts_requests_empty
                                            }
                                        ),
                                        stringResource(R.string.contacts_empty_explanation)
                                    )
                                }
                            }
                            items(rows, key = { "person:" + it.id }, contentType = { "identity" }) { row ->
                                KitRow(
                                    row.label,
                                    { onAction(ContactsAction.Select(row.id)) },
                                    value = row.principal,
                                    leading = { KitAvatar(row.label.take(1), 36f, KitAvatarKind.Person) },
                                    trailingContent = { Text(row.status.render()) },
                                    testTag = row.id
                                )
                            }
                        }
                        ContactsPage.DETAIL, ContactsPage.SAFETY, ContactsPage.DECLINE -> state.selected?.let { row ->
                            item("identity") {
                                KitRow(
                                    row.label,
                                    {},
                                    value = row.principal,
                                    leading = { KitAvatar(row.label.take(1), 36f, KitAvatarKind.Person) },
                                    trailing = KitRowTrailing.None,
                                    testTag = row.id
                                )
                                Text(row.status.render())
                                row.note?.let { Text(it) }
                                row.count?.let { Text(it.render()) }
                                row.expires?.let { Text(stringResource(R.string.contacts_expires, it.render())) }
                            }
                            when (state.page) {
                                ContactsPage.SAFETY -> item("safety") {
                                    state.safety?.let { safety ->
                                        Text(safety.number)
                                        Text(stringResource(R.string.contacts_fingerprint, safety.fingerprint))
                                    }
                                    KitSecurityWarning(
                                        stringResource(R.string.contacts_safety),
                                        stringResource(R.string.contacts_safety_explanation),
                                        KitWarningTone.Warning
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
                                    TextButton(onClick = { onAction(ContactsAction.Page(ContactsPage.SAFETY)) }) {
                                        Text(stringResource(R.string.contacts_safety))
                                    }
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
                        TextButton(onClick = { onAction(ContactsAction.Page(ContactsPage.ADD)) }) {
                            Text(stringResource(R.string.contacts_add))
                        }
                        if (state.tab == ContactsTab.STRANGERS) {
                            TextButton(onClick = { onAction(ContactsAction.Page(ContactsPage.BATCH)) }) {
                                Text(stringResource(R.string.contacts_batch))
                            }
                        }
                    }
                }
                if (state.page == ContactsPage.DETAIL && state.selected?.inbound == true) {
                    Row(Modifier.fillMaxWidth().padding(theme.gap.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { onAction(ContactsAction.Page(ContactsPage.DECLINE)) }) {
                            Text(stringResource(R.string.contacts_decline))
                        }
                        OperationButton(R.string.contacts_reply, ContactOperation.REPLY, state.loading, onAction)
                        OperationButton(R.string.contacts_accept, ContactOperation.ACCEPT, state.loading, onAction)
                    }
                }
            }
        }
    }
    state.approval?.let { approval ->
        HostConfirmationDialog(onDismissRequest = { onAction(ContactsAction.Deny) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                Text(stringResource(R.string.contacts_confirm), color = theme.text)
                Text(approval.operation.render())
                Text(stringResource(R.string.contacts_confirm_account, approval.account))
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(approval.targets) { target -> Text(target) }
                }
                HostConfirmationActions(
                    approval,
                    stringResource(R.string.contacts_deny),
                    stringResource(R.string.contacts_confirm_action),
                    stringResource(R.string.contacts_confirm_accessibility, approval.operation.render()),
                    onDeny = { onAction(ContactsAction.Deny) },
                    onConfirm = { onAction(ContactsAction.Confirm) }
                )
            }
        }
    }
}

@Composable
private fun OperationButton(label: Int, operation: ContactOperation, disabled: Boolean, onAction: (ContactsAction) -> Unit) {
    TextButton(enabled = !disabled, onClick = { onAction(ContactsAction.Operate(operation)) }) { Text(stringResource(label)) }
}
