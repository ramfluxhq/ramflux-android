// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.groups.impl.R

@Composable
fun GroupsScreen(state: GroupsState, onAction: (GroupsAction) -> Unit, modifier: Modifier = Modifier, rooted: Boolean = false) {
    val theme = LocalRFTheme.current
    val title = stringResource(
        when (state.page) {
            GroupsPage.LIST -> R.string.groups_title
            GroupsPage.CREATE -> R.string.groups_create
            GroupsPage.DETAIL -> R.string.groups_detail
            GroupsPage.MEMBERS -> R.string.groups_members
            GroupsPage.MEMBER -> R.string.groups_member_title
            GroupsPage.INVITE -> R.string.groups_add
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
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(vertical = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    notices(state, rooted, onAction)
                    when (state.page) {
                        GroupsPage.LIST -> list(state, rooted, onAction)
                        GroupsPage.CREATE -> item("create", contentType = "body") {
                            Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                                GroupName(state.name, onAction)
                                Text(stringResource(R.string.groups_creator_only), color = theme.secondary)
                                KitButton(
                                    stringResource(R.string.groups_review_create),
                                    { onAction(GroupsAction.Create) },
                                    Modifier.fillMaxWidth(),
                                    enabled = !state.loading && !state.busy && state.pending == null && state.name.isNotBlank(),
                                    tag = "groups-review-create"
                                )
                            }
                        }
                        GroupsPage.DETAIL -> detail(state, onAction)
                        GroupsPage.MEMBERS -> members(state, onAction)
                        GroupsPage.MEMBER -> state.selectedMember?.let { member(it, state, onAction) }
                        GroupsPage.INVITE -> invite(state, onAction)
                        GroupsPage.REQUESTS -> requests(state, onAction)
                        GroupsPage.INVITATION -> state.selectedInvitation?.let { invitation(it, state, onAction) }
                    }
                }
                // Only once the first page has loaded: while it loads, an empty account would show this button and
                // then swap it for the empty state's own create action.
                if (state.page == GroupsPage.LIST && state.loaded && !state.emptyRoot) {
                    TextButton(
                        onClick = { onAction(GroupsAction.Page(GroupsPage.CREATE)) },
                        modifier = Modifier.testTag("groups-create")
                    ) { Text(stringResource(R.string.groups_create)) }
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

private fun LazyListScope.notices(state: GroupsState, rooted: Boolean, onAction: (GroupsAction) -> Unit) {
    if (state.busy) item("busy", contentType = "notice") { KitEmpty("loader-circle", stringResource(R.string.groups_loading), "") }
    // A failed first read is a centred failure with retry, never an empty list; later failures keep the rows.
    state.problem?.let { problem ->
        item("problem", contentType = "notice") {
            if (!state.loaded) {
                KitEmpty(
                    "circle-alert",
                    stringResource(R.string.groups_error),
                    problem.render(),
                    action = stringResource(R.string.groups_retry),
                    onAction = { onAction(GroupsAction.Refresh) },
                    tag = "groups-error"
                )
            } else {
                Notice(problem.render())
            }
        }
    }
    state.notice?.let { item("notice", contentType = "notice") { Notice(it.render(), "groups-notice") } }
    state.pending?.let { id -> item("pending", contentType = "notice") { Notice(stringResource(R.string.groups_pending, id)) } }
    if (state.accountRequired) {
        item("account", contentType = "notice") {
            KitEmpty(
                "user-round",
                stringResource(R.string.groups_account_needed),
                stringResource(R.string.groups_account_help),
                action = stringResource(R.string.groups_account_action),
                onAction = { onAction(GroupsAction.SetupAccount) },
                tag = if (rooted) "groups-account" else null
            )
        }
    }
}

@Composable
private fun Notice(text: String, tag: String? = null) {
    val theme = LocalRFTheme.current
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        color = theme.secondary
    )
}

/** Placeholder rows while the first page of a list loads. */
private fun LazyListScope.skeleton(state: GroupsState, tag: String): Boolean {
    if (!state.loading || state.loaded) return false
    items(SKELETON_ROWS, key = { "skeleton:$it" }, contentType = { "skeleton" }) { KitSkeletonRow(it, tag = tag) }
    return true
}

/** The window edges: reaching one reads the page beyond it through that end's cursor. */
private fun LazyListScope.before(state: GroupsState, list: GroupList, first: String?, onAction: (GroupsAction) -> Unit) {
    if (!state.paging(list).before) return
    item("before:$list", contentType = "more") {
        // Rows before the window were dropped to bound memory; reaching the start reads them again.
        LaunchedEffect(first) { onAction(GroupsAction.More(list, forward = false)) }
        KitSkeletonRow(0)
    }
}

private fun LazyListScope.after(state: GroupsState, list: GroupList, last: String?, onAction: (GroupsAction) -> Unit) {
    if (!state.paging(list).more) return
    item("more:$list", contentType = "more") {
        LaunchedEffect(last) { onAction(GroupsAction.More(list)) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(
                enabled = !state.loadingMore,
                onClick = { onAction(GroupsAction.More(list)) },
                modifier = Modifier.testTag("groups-more-$list")
            ) { Text(stringResource(R.string.groups_load_more)) }
        }
    }
}

/** MOB-C01 and MOB-C15: joined groups, then pending invitations. */
private fun LazyListScope.list(state: GroupsState, rooted: Boolean, onAction: (GroupsAction) -> Unit) {
    // MOB-C15: with no group and no filter the board shows only the empty state and its create action.
    if (state.emptyRoot) {
        item("empty", contentType = "notice") {
            // Placed as on the board: three parts of the free space above, seven below.
            Column(Modifier.fillParentMaxSize()) {
                Spacer(Modifier.weight(EMPTY_ABOVE))
                KitEmpty(
                    "users-round",
                    stringResource(R.string.groups_empty),
                    stringResource(R.string.groups_empty_help),
                    action = stringResource(R.string.groups_create),
                    onAction = { onAction(GroupsAction.Page(GroupsPage.CREATE)) },
                    tag = if (rooted) "groups-empty" else null
                )
                Spacer(Modifier.weight(EMPTY_BELOW))
            }
        }
        return
    }
    item("search", contentType = "search") {
        Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
            KitSearchField(state.query, { onAction(GroupsAction.Query(it)) }, stringResource(R.string.groups_filter), tag = "group-search")
        }
    }
    if (skeleton(state, "groups-skeleton")) return
    if (state.loaded && state.groups.isEmpty() && state.query.isNotBlank() && state.problem == null) {
        item("no-match", contentType = "notice") { KitEmpty("search-x", stringResource(R.string.groups_no_matches), "") }
    }
    before(state, GroupList.GROUPS, state.groups.firstOrNull()?.id, onAction)
    items(state.groups, key = { "group:" + it.id }, contentType = { "group" }) { row ->
        KitRow(
            row.title,
            { onAction(GroupsAction.Page(GroupsPage.DETAIL, row.id)) },
            value = row.count.render().takeIf { it.isNotEmpty() },
            trailing = KitRowTrailing.None,
            peer = true,
            testTag = row.id
        )
    }
    after(state, GroupList.GROUPS, state.groups.lastOrNull()?.id, onAction)
    if (state.invitations.isNotEmpty()) {
        item("invitations-title", contentType = "section") { Notice(stringResource(R.string.groups_invitations)) }
    }
    items(state.invitations, key = { "invite:" + it.id }, contentType = { "invitation" }) { row ->
        KitRow(
            row.title,
            { onAction(GroupsAction.Page(GroupsPage.INVITATION, row.id)) },
            value = row.expiry.render().takeIf { it.isNotEmpty() },
            trailing = KitRowTrailing.None,
            peer = true,
            testTag = row.id
        )
    }
    after(state, GroupList.INVITATIONS, state.invitations.lastOrNull()?.id, onAction)
}

private fun LazyListScope.identity(state: GroupsState) {
    item("identity", contentType = "identity") {
        val theme = LocalRFTheme.current
        Row(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp), verticalAlignment = Alignment.CenterVertically) {
            KitIconAvatar("users-round", PROFILE_AVATAR)
            Column(Modifier.padding(start = theme.gap.dp).weight(1f)) {
                UntrustedText(
                    state.title ?: state.selected.orEmpty(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("groups-title")
                )
                val count = state.memberCount.render()
                if (count.isNotEmpty()) Text(count, color = theme.secondary)
                state.ownRole?.let { Text(stringResource(R.string.groups_own_role, it.render()), color = theme.secondary) }
            }
        }
    }
}

/** MOB-C07 (group details), built from the Kit rows; no export exists yet. */
private fun LazyListScope.detail(state: GroupsState, onAction: (GroupsAction) -> Unit) {
    identity(state)
    item("facts", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
            LtrCode(state.selected.orEmpty(), tag = "groups-id")
            Text(stringResource(R.string.groups_epoch, state.epoch.render()), color = theme.secondary)
            if (!state.authorityFresh && state.loaded) Text(stringResource(R.string.groups_last_confirmed), color = theme.secondary)
        }
    }
    item("actions", contentType = "rows") {
        Column {
            KitRow(
                stringResource(R.string.groups_messages),
                { onAction(GroupsAction.OpenChat) },
                icon = "message-circle",
                enabled = !state.loading && !state.busy,
                testTag = "groups-open-chat"
            )
            KitRow(
                stringResource(R.string.groups_members),
                { onAction(GroupsAction.Page(GroupsPage.MEMBERS, state.selected)) },
                icon = "users-round",
                value = state.memberCount.render().takeIf { it.isNotEmpty() },
                testTag = "groups-open-members"
            )
            KitRow(
                stringResource(R.string.groups_requests),
                { onAction(GroupsAction.Page(GroupsPage.REQUESTS, state.selected)) },
                icon = "clipboard-list",
                testTag = "groups-open-requests"
            )
        }
    }
    item("manage", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            if (state.canManage) {
                GroupName(state.name, onAction)
                KitButton(
                    stringResource(R.string.groups_rename),
                    { onAction(GroupsAction.Rename) },
                    Modifier.fillMaxWidth(),
                    KitButtonKind.Secondary,
                    enabled = !state.busy && state.pending == null && state.name.isNotBlank(),
                    tag = "groups-rename"
                )
            } else if (state.loaded) {
                Text(stringResource(R.string.groups_permissions), color = theme.secondary)
            }
        }
    }
    item("unavailable-controls", contentType = "rows") {
        Column {
            KitRow(
                stringResource(R.string.groups_transfer),
                { onAction(GroupsAction.Unavailable(GroupControl.TRANSFER)) },
                icon = "user-cog",
                value = stringResource(R.string.groups_not_on_phone),
                testTag = "groups-transfer"
            )
            KitRow(
                stringResource(R.string.groups_leave),
                { onAction(GroupsAction.Leave) },
                icon = "user-minus",
                value = if (state.loaded && !state.canLeave) stringResource(R.string.groups_leave_owner) else null,
                enabled = state.canLeave && state.authorityFresh && !state.busy && state.pending == null,
                testTag = "groups-leave"
            )
        }
    }
}

/** MOB-C08 (members and roles): the roster window with a filter; managers also reach the invite picker. */
private fun LazyListScope.members(state: GroupsState, onAction: (GroupsAction) -> Unit) {
    item("search", contentType = "search") {
        Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
            KitSearchField(
                state.query,
                { onAction(GroupsAction.Query(it)) },
                stringResource(R.string.groups_member_filter),
                tag = "member-search"
            )
        }
    }
    if (state.canManage) {
        item("invite", contentType = "rows") {
            KitRow(
                stringResource(R.string.groups_add),
                { onAction(GroupsAction.Page(GroupsPage.INVITE, state.selected)) },
                icon = "user-plus",
                enabled = !state.busy && state.pending == null,
                testTag = "groups-open-invite"
            )
        }
    }
    if (!state.authorityFresh && state.loaded) {
        item(
            "stale",
            contentType = "notice"
        ) { Notice(stringResource(R.string.groups_last_confirmed)) }
    }
    if (skeleton(state, "members-skeleton")) return
    if (state.loaded && state.members.isEmpty() && state.problem == null) {
        item("empty", contentType = "notice") {
            KitEmpty(
                "users-round",
                stringResource(if (state.query.isBlank()) R.string.groups_no_members else R.string.groups_no_matches),
                ""
            )
        }
    }
    before(state, GroupList.MEMBERS, state.members.firstOrNull()?.id, onAction)
    items(state.members, key = { "member:" + it.id }, contentType = { "member" }) { member ->
        val name = member.label
        val role = member.role.render()
        KitRow(
            name,
            { onAction(GroupsAction.Page(GroupsPage.MEMBER, member.id)) },
            value = if (member.active) role else stringResource(R.string.groups_role_inactive, role),
            leading = { KitAvatar(name.take(1), AVATAR, KitAvatarKind.Person) },
            peer = true,
            testTag = "member:" + member.id,
            ai = member.ai
        )
    }
    after(state, GroupList.MEMBERS, state.members.lastOrNull()?.id, onAction)
}

/** MOB-C09 and MOB-C10: one member's identity, role change and removal (or why they are not offered). */
private fun LazyListScope.member(member: MemberRow, state: GroupsState, onAction: (GroupsAction) -> Unit) {
    item("member", contentType = "identity") {
        val theme = LocalRFTheme.current
        val name = member.label
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KitAvatar(name.take(1), PROFILE_AVATAR, KitAvatarKind.Person)
                Column(Modifier.padding(start = theme.gap.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
                        UntrustedText(name, style = MaterialTheme.typography.titleLarge)
                        if (member.ai) KitAiMarker()
                    }
                    if (member.name != null && member.principal.isNotEmpty()) LtrCode(member.principal)
                    Text(member.role.render(), color = theme.secondary, modifier = Modifier.testTag("member-role"))
                    if (!member.active) Text(stringResource(R.string.groups_inactive), color = theme.secondary)
                }
            }
            Text(stringResource(R.string.groups_device), color = theme.secondary)
            LtrCode(member.device)
            member.root?.let {
                Text(stringResource(R.string.groups_identity_root), color = theme.secondary)
                LtrCode(it)
            }
        }
    }
    item("member-actions", contentType = "body") {
        val theme = LocalRFTheme.current
        val enabled = !state.busy && state.pending == null && member.manageable
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            if (member.manageable) {
                KitButton(
                    stringResource(if (member.admin) R.string.groups_demote else R.string.groups_promote),
                    { onAction(GroupsAction.Role(member.id, !member.admin)) },
                    Modifier.fillMaxWidth(),
                    KitButtonKind.Secondary,
                    enabled = enabled,
                    tag = "member-role-change"
                )
                KitButton(
                    stringResource(R.string.groups_remove),
                    { onAction(GroupsAction.Remove(member.id)) },
                    Modifier.fillMaxWidth(),
                    KitButtonKind.Destructive,
                    enabled = enabled,
                    tag = "member-remove"
                )
            } else {
                Text(stringResource(R.string.groups_member_fixed), color = theme.secondary)
            }
        }
    }
}

/** The invite picker: accepted contacts with an identity root, windowed and filtered like the contacts list. */
private fun LazyListScope.invite(state: GroupsState, onAction: (GroupsAction) -> Unit) {
    item("search", contentType = "search") {
        Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
            KitSearchField(
                state.query,
                { onAction(GroupsAction.Query(it)) },
                stringResource(R.string.groups_contact_filter),
                tag = "contact-search"
            )
        }
    }
    if (skeleton(state, "contacts-skeleton")) return
    if (state.loaded && state.contacts.isEmpty() && state.problem == null) {
        item("empty", contentType = "notice") { KitEmpty("contact-round", stringResource(R.string.groups_no_contacts), "") }
    }
    before(state, GroupList.CONTACTS, state.contacts.firstOrNull()?.id, onAction)
    items(state.contacts, key = { "contact:" + it.id }, contentType = { "contact" }) { contact ->
        KitRow(
            contact.title,
            { onAction(GroupsAction.Add(contact.id)) },
            value = contact.count.render(),
            leading = { KitAvatar(contact.title.take(1), AVATAR, KitAvatarKind.Person) },
            enabled = state.canManage && !state.busy && state.pending == null,
            trailing = KitRowTrailing.None,
            peer = true,
            testTag = "contact:" + contact.id
        )
    }
    after(state, GroupList.CONTACTS, state.contacts.lastOrNull()?.id, onAction)
}

private fun LazyListScope.requests(state: GroupsState, onAction: (GroupsAction) -> Unit) {
    if (skeleton(state, "requests-skeleton")) return
    if (state.loaded && state.requests.isEmpty() && state.problem == null) {
        item("no-requests", contentType = "notice") { KitEmpty("clipboard-list", stringResource(R.string.groups_no_requests), "") }
    }
    before(state, GroupList.REQUESTS, state.requests.firstOrNull()?.id, onAction)
    items(state.requests, key = { "request:" + it.id }, contentType = { "request" }) { request ->
        val theme = LocalRFTheme.current
        Column(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp)) {
            Text(request.status.render())
            LtrCode(request.id)
            Text(request.expiry.render(), color = theme.secondary)
        }
    }
    after(state, GroupList.REQUESTS, state.requests.lastOrNull()?.id, onAction)
}

private fun LazyListScope.invitation(invitation: InviteRow, state: GroupsState, onAction: (GroupsAction) -> Unit) {
    item("invitation", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
            UntrustedText(invitation.title, style = MaterialTheme.typography.titleLarge)
            LtrCode(invitation.id)
            Text(uiText(R.string.groups_creator, UiText.Untrusted(invitation.creator)).render())
            Text(uiText(R.string.groups_inviter, UiText.Untrusted(invitation.inviter)).render())
            Text(invitation.expiry.render(), color = theme.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitButton(
                    stringResource(R.string.groups_decline),
                    { onAction(GroupsAction.Invitation(false)) },
                    Modifier.weight(1f),
                    KitButtonKind.Secondary,
                    enabled = !state.busy && state.pending == null,
                    tag = "groups-invitation-decline"
                )
                KitButton(
                    stringResource(R.string.groups_accept),
                    { onAction(GroupsAction.Invitation(true)) },
                    Modifier.weight(1f),
                    enabled = !state.busy && state.pending == null,
                    tag = "groups-invitation-accept"
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

/** No group, no invitation, no filter and nothing failed: the MOB-C15 empty state. */
private val GroupsState.emptyRoot
    get() = loaded && groups.isEmpty() && invitations.isEmpty() && query.isBlank() && problem == null && !accountRequired

private const val SKELETON_ROWS = 5
private const val EMPTY_ABOVE = 3f
private const val EMPTY_BELOW = 7f
private const val AVATAR = 40f
private const val PROFILE_AVATAR = 56f
