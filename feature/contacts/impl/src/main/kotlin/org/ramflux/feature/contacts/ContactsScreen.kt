// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    val subtitle = state.accountLabel?.takeIf { it.isNotEmpty() && state.page == ContactsPage.LIST }
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                KitPageHeader(
                    stringResource(
                        when (state.page) {
                            ContactsPage.LIST -> R.string.contacts_title
                            ContactsPage.DETAIL -> R.string.contacts_profile
                            ContactsPage.ADD -> R.string.contacts_add
                            ContactsPage.SAFETY -> R.string.contacts_safety_title
                            ContactsPage.DECLINE -> R.string.contacts_decline
                            ContactsPage.BATCH -> R.string.contacts_batch
                        }
                    ),
                    subtitle = subtitle,
                    onBack = { onAction(ContactsAction.Back) }
                )
                if (state.page == ContactsPage.LIST) Tabs(state, onAction)
                KitPullRefresh(
                    enabled = state.page == ContactsPage.LIST,
                    refreshing = state.loading,
                    onRefresh = { onAction(ContactsAction.Refresh) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = theme.gap.dp)) {
                        notices(state, onAction)
                        when (state.page) {
                            ContactsPage.LIST -> list(state, onAction)
                            ContactsPage.DETAIL -> state.selected?.let { profile(it, onAction) }
                            ContactsPage.SAFETY -> state.selected?.let { safety(it, state, onAction) }
                            ContactsPage.DECLINE -> state.selected?.let { row ->
                                identity(row)
                                item("decline", contentType = "body") {
                                    Column(
                                        Modifier.padding(horizontal = theme.gap.dp),
                                        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                                    ) {
                                        Text(stringResource(R.string.contacts_decline_explanation), color = theme.secondary)
                                        KitButton(
                                            stringResource(R.string.contacts_decline),
                                            {
                                                onAction(ContactsAction.Operate(ContactOperation.DECLINE))
                                            },
                                            Modifier.fillMaxWidth(),
                                            KitButtonKind.Secondary,
                                            enabled = !state.loading,
                                            tag = "contacts-decline"
                                        )
                                        KitButton(
                                            stringResource(R.string.contacts_block),
                                            {
                                                onAction(ContactsAction.Operate(ContactOperation.BLOCK))
                                            },
                                            Modifier.fillMaxWidth(),
                                            KitButtonKind.Destructive,
                                            enabled = !state.loading,
                                            tag = "contacts-block"
                                        )
                                    }
                                }
                            }
                            ContactsPage.ADD -> item("add", contentType = "body") {
                                Column(
                                    Modifier.padding(horizontal = theme.gap.dp),
                                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                                ) {
                                    OutlinedTextField(
                                        state.address,
                                        onValueChange = { onAction(ContactsAction.Address(it)) },
                                        label = { Text(stringResource(R.string.contacts_address)) },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !state.submitting,
                                        maxLines = 3
                                    )
                                    Text(stringResource(R.string.contacts_add_explanation), color = theme.secondary)
                                    // An @name is looked up first; its card then sends the request (the approval sheet still confirms it).
                                    state.visibleUsername()?.let { UsernameLookupCard(it, enabled = !state.loading, onAction = onAction) }
                                    KitButton(
                                        stringResource(
                                            if (state.usernameNeedsLookup()) {
                                                R.string.contacts_find_username
                                            } else {
                                                R.string.contacts_send_request
                                            }
                                        ),
                                        {
                                            onAction(ContactsAction.Operate(ContactOperation.ADD))
                                        },
                                        Modifier.fillMaxWidth(),
                                        enabled = !state.loading && state.address.isNotBlank(),
                                        tag = "contacts-send"
                                    )
                                }
                            }
                            ContactsPage.BATCH -> {
                                item("batch-instructions", contentType = "body") {
                                    Text(stringResource(R.string.contacts_batch_explanation), Modifier.padding(horizontal = theme.gap.dp))
                                }
                                items(state.rows, key = { "select:" + it.id }, contentType = { "select" }) { row ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp).semantics(mergeDescendants = true) {}
                                            .toggleable(
                                                row.id in state.selectedIds,
                                                role = Role.Checkbox,
                                                onValueChange = { onAction(ContactsAction.Toggle(row.id)) }
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(row.id in state.selectedIds, onCheckedChange = null)
                                        UntrustedText(row.label, kind = UntrustedKind.Name)
                                    }
                                }
                                item("batch-action", contentType = "body") {
                                    KitButton(
                                        stringResource(R.string.contacts_decline_selected),
                                        { onAction(ContactsAction.Operate(ContactOperation.BATCH_DECLINE)) },
                                        Modifier.fillMaxWidth().padding(theme.gap.dp),
                                        KitButtonKind.Secondary,
                                        enabled = !state.loading && state.selectedIds.isNotEmpty()
                                    )
                                }
                            }
                        }
                    }
                }
                Footer(state, onAction)
            }
        }
    }
    state.approval?.let { approval -> Confirmation(approval, onAction) }
}

@Composable
private fun Tabs(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    val theme = LocalRFTheme.current
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp), horizontalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
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
                                ContactsTab.BLOCKED -> R.string.contacts_tab_blocked
                            }
                        )
                    )
                },
                modifier = Modifier.testTag("tab:" + tab.name)
            )
        }
    }
}

private fun LazyListScope.notices(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    state.operationNotice?.let { notice -> item("operation-notice", contentType = "notice") { Notice(notice.render()) } }
    items(state.uncertain.size, key = { "uncertain:$it" }, contentType = { "notice" }) { index -> Notice(state.uncertain[index].render()) }
    state.unavailable?.let { message ->
        item("unavailable", contentType = "notice") {
            KitEmpty("inbox", stringResource(R.string.contacts_unavailable), message.render(), tag = "contacts-unavailable")
        }
    }
    if (state.accountRequired) {
        item("account", contentType = "notice") {
            KitEmpty(
                "inbox",
                stringResource(R.string.contacts_account_required),
                stringResource(R.string.contacts_account_explanation),
                action = stringResource(R.string.contacts_account_action),
                onAction = { onAction(ContactsAction.SetupAccount) }
            )
        }
    }
    // A failed first read is a centred failure with retry, never an empty list; later failures keep the rows.
    state.problem?.let { message ->
        item("error", contentType = "notice") {
            if (state.page == ContactsPage.LIST && !state.loaded) {
                KitEmpty(
                    "circle-alert",
                    stringResource(
                        R.string.contacts_error
                    ),
                    message.render(),
                    action = stringResource(R.string.contacts_retry),
                    onAction = {
                        onAction(ContactsAction.Refresh)
                    },
                    tag = "contacts-error"
                )
            } else {
                Notice(message.render())
            }
        }
    }
}

@Composable
private fun Notice(text: String) {
    val theme = LocalRFTheme.current
    Text(text, Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = theme.gap.dp / 2), color = theme.secondary)
}

private fun LazyListScope.list(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    item("search", contentType = "search") {
        Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
            KitSearchField(
                state.query,
                { onAction(ContactsAction.Query(it)) },
                stringResource(R.string.contacts_search),
                tag = "contacts-search"
            )
        }
    }
    if (state.loading && !state.loaded) {
        items(SKELETON_ROWS, key = { "skeleton:$it" }, contentType = { "skeleton" }) { KitSkeletonRow(it, tag = "contacts-skeleton") }
        return
    }
    if (state.loaded && state.rows.isEmpty() && state.problem == null) {
        item("empty", contentType = "notice") {
            KitEmpty(
                "inbox",
                stringResource(
                    when {
                        state.query.isNotBlank() -> R.string.contacts_no_matches
                        state.tab == ContactsTab.CONTACTS -> R.string.contacts_empty
                        state.tab == ContactsTab.BLOCKED -> R.string.contacts_blocked_empty
                        else -> R.string.contacts_requests_empty
                    }
                ),
                stringResource(
                    if (state.tab == ContactsTab.CONTACTS) {
                        R.string.contacts_empty_explanation
                    } else {
                        R.string.contacts_requests_empty_explanation
                    }
                ),
                action = if (state.tab == ContactsTab.CONTACTS && state.query.isBlank()) stringResource(R.string.contacts_add) else null,
                onAction = { onAction(ContactsAction.Page(ContactsPage.ADD)) },
                tag = "contacts-empty"
            )
        }
    }
    if (state.hasBefore) {
        item("before", contentType = "more") {
            // Rows before the window were dropped to bound memory; reaching the start reads them again.
            LaunchedEffect(state.rows.firstOrNull()?.id) { onAction(ContactsAction.LoadPrevious) }
            KitSkeletonRow(0, tag = "contacts-before")
        }
    }
    items(state.rows, key = { "person:" + it.id }, contentType = { if (it.request) "request" else "contact" }) { row ->
        KitRow(
            row.label,
            { onAction(ContactsAction.Select(row.id)) },
            value = row.status.render(),
            leading = { KitAvatar(row.label.take(1), AVATAR, KitAvatarKind.Person) },
            peer = true,
            testTag = row.id,
            ai = row.ai
        )
    }
    if (state.hasMore) {
        item("more", contentType = "more") {
            LaunchedEffect(state.rows.lastOrNull()?.id) { onAction(ContactsAction.LoadMore) }
            Box(Modifier.fillMaxWidth().padding(LocalRFTheme.current.gap.dp), contentAlignment = Alignment.Center) {
                TextButton(
                    enabled = !state.loadingMore,
                    onClick = { onAction(ContactsAction.LoadMore) },
                    modifier = Modifier.testTag("contacts-more")
                ) {
                    Text(stringResource(R.string.contacts_load_more))
                }
            }
        }
    }
}

private fun LazyListScope.identity(row: PersonItem) {
    item("identity", contentType = "identity") {
        val theme = LocalRFTheme.current
        Row(
            Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp, vertical = theme.gap.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KitAvatar(row.label.take(1), PROFILE_AVATAR, KitAvatarKind.Person)
            Column(Modifier.padding(start = theme.gap.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
                    UntrustedText(row.label, kind = UntrustedKind.Name, modifier = Modifier.testTag(row.id))
                    if (row.ai) KitAiMarker()
                }
                Text(row.status.render(), color = theme.secondary)
            }
        }
    }
}

/** MOB-F03 (profile half), F05/F06 (stranger), F13–F17 (stranger actions), sent and blocked variants. */
private fun LazyListScope.profile(row: PersonItem, onAction: (ContactsAction) -> Unit) {
    identity(row)
    item("details", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
            row.note?.let { UntrustedText(it, kind = UntrustedKind.Excerpt, modifier = Modifier.padding(horizontal = theme.gap.dp)) }
            row.count?.let { Notice(it.render()) }
            row.expires?.let { Notice(stringResource(R.string.contacts_expires, it.render())) }
            if (!row.request && !row.blocked && row.root != null) {
                KitRow(
                    stringResource(R.string.contacts_message),
                    { onAction(ContactsAction.Message) },
                    icon = "message-square",
                    testTag = "contacts-message"
                )
            }
            if (!row.request && !row.blocked) {
                KitRow(
                    stringResource(R.string.contacts_safety),
                    { onAction(ContactsAction.Page(ContactsPage.SAFETY)) },
                    value = stringResource(
                        if (row.verified) R.string.contacts_safety_state_verified else R.string.contacts_safety_state_unverified
                    ),
                    icon = "shield-check",
                    testTag = "contacts-open-safety"
                )
            }
            when {
                row.inbound -> Notice(stringResource(R.string.contacts_stranger_explanation))
                row.request -> Notice(stringResource(R.string.contacts_sent_explanation))
                row.blocked -> Notice(stringResource(R.string.contacts_blocked_explanation))
            }
        }
    }
}

/** MOB-F03 (safety half) and F09–F12: state, number, and the verify decision. */
private fun LazyListScope.safety(row: PersonItem, state: ContactsState, onAction: (ContactsAction) -> Unit) {
    identity(row)
    item("safety", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            val safety = state.safety
            if (safety == null) {
                if (state.loading) KitSkeletonRow(0)
                return@Column
            }
            Surface(color = theme.surface, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.contacts_verification_state))
                        Text(safety.state.render(), color = theme.secondary, modifier = Modifier.testTag("contacts-safety-state"))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.contacts_safety))
                    // Left to right in groups of five in every locale; the fingerprint is a typed code argument,
                    // isolated inside the translated sentence.
                    LtrCode(safety.number, groupSize = SAFETY_GROUP, tag = "contacts-safety-number")
                    Text(uiText(R.string.contacts_fingerprint, UiText.Code(safety.fingerprint)).render(), color = theme.secondary)
                }
            }
            Text(stringResource(R.string.contacts_safety_compare_title))
            KitSecurityWarning(
                stringResource(R.string.contacts_safety_changed_title),
                stringResource(R.string.contacts_safety_explanation),
                KitWarningTone.Warning
            )
            if (!safety.verified && !safety.verifiable) Notice(stringResource(R.string.contacts_safety_binding_unavailable))
        }
    }
}

@Composable
private fun Footer(state: ContactsState, onAction: (ContactsAction) -> Unit) {
    val theme = LocalRFTheme.current
    val row = state.selected
    val modifier = Modifier.fillMaxWidth().padding(theme.gap.dp)
    when {
        state.page == ContactsPage.LIST -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            KitButton(stringResource(R.string.contacts_add), {
                onAction(ContactsAction.Page(ContactsPage.ADD))
            }, Modifier.weight(1f), KitButtonKind.Secondary, tag = "contacts-add")
            if (state.tab == ContactsTab.STRANGERS && state.rows.isNotEmpty()) {
                KitButton(stringResource(R.string.contacts_batch), {
                    onAction(ContactsAction.Page(ContactsPage.BATCH))
                }, Modifier.weight(1f), KitButtonKind.Secondary, tag = "contacts-batch")
            }
        }
        row == null -> Unit
        state.page == ContactsPage.SAFETY && state.safety?.verified == false -> Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            KitButton(stringResource(R.string.contacts_not_now), {
                onAction(ContactsAction.Back)
            }, Modifier.weight(1f), KitButtonKind.Secondary, tag = "contacts-not-now")
            KitButton(
                stringResource(R.string.contacts_numbers_match),
                { onAction(ContactsAction.Operate(ContactOperation.VERIFY)) },
                Modifier.weight(1f),
                enabled = !state.loading && state.safety.verifiable,
                tag = "contacts-verify"
            )
        }
        state.page != ContactsPage.DETAIL -> Unit
        row.inbound -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            KitButton(stringResource(R.string.contacts_decline), {
                onAction(ContactsAction.Page(ContactsPage.DECLINE))
            }, Modifier.weight(1f), KitButtonKind.Secondary, enabled = !state.loading, tag = "contacts-open-decline")
            KitButton(stringResource(R.string.contacts_accept), {
                onAction(ContactsAction.Operate(ContactOperation.ACCEPT))
            }, Modifier.weight(1f), enabled = !state.loading, tag = "contacts-accept")
        }
        row.request -> Unit
        row.blocked -> KitButton(stringResource(R.string.contacts_unblock), {
            onAction(ContactsAction.Operate(ContactOperation.UNBLOCK))
        }, modifier, KitButtonKind.Secondary, enabled = !state.loading, tag = "contacts-unblock")
        else -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            KitButton(stringResource(R.string.contacts_remove), {
                onAction(ContactsAction.Operate(ContactOperation.REMOVE))
            }, Modifier.weight(1f), KitButtonKind.Secondary, enabled = !state.loading, tag = "contacts-remove")
            KitButton(stringResource(R.string.contacts_block), {
                onAction(ContactsAction.Operate(ContactOperation.BLOCK_CONTACT))
            }, Modifier.weight(1f), KitButtonKind.Destructive, enabled = !state.loading, tag = "contacts-block-contact")
        }
    }
}

@Composable
private fun Confirmation(approval: ContactApproval, onAction: (ContactsAction) -> Unit) {
    val theme = LocalRFTheme.current
    HostConfirmationDialog(onDismissRequest = { onAction(ContactsAction.Deny) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            Text(stringResource(R.string.contacts_confirm), color = theme.text)
            Text(approval.operation.render())
            approval.explanation?.let { Text(it.render(), color = theme.secondary) }
            Text(stringResource(R.string.contacts_confirm_account, approval.account))
            LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                items(approval.targets.size, key = { it }) { index -> UntrustedText(approval.targets[index], kind = UntrustedKind.Name) }
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

private const val SAFETY_GROUP = 5
private const val SKELETON_ROWS = 5
private const val AVATAR = 40f
private const val PROFILE_AVATAR = 56f

/** The directory's answer for an `@name`: the name it found with its published details, or the typed refusal. */
@Composable
private fun UsernameLookupCard(lookup: UsernameLookup, enabled: Boolean, onAction: (ContactsAction) -> Unit) {
    val theme = LocalRFTheme.current
    when (lookup) {
        is UsernameLookup.Found -> Column(
            Modifier.fillMaxWidth().testTag("contacts-username-found"),
            verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
        ) {
            Text(stringResource(R.string.contacts_username_found, lookup.name))
            lookup.displayName?.let { Text(it) }
            lookup.bio?.let { Text(it, color = theme.secondary) }
            KitButton(stringResource(R.string.contacts_send_request), {
                onAction(ContactsAction.Operate(ContactOperation.ADD))
            }, Modifier.fillMaxWidth(), enabled = enabled, tag = "contacts-username-send")
        }
        is UsernameLookup.Failed -> Text(
            lookup.message.render(),
            color = theme.danger,
            modifier = Modifier.testTag("contacts-username-failed")
        )
    }
}
