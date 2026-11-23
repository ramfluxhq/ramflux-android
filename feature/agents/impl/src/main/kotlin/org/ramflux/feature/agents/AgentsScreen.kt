// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
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
import org.ramflux.feature.agents.impl.R

@Composable
fun AgentsScreen(state: AgentsState, onAction: (AgentsAction) -> Unit, modifier: Modifier = Modifier, rooted: Boolean = false) {
    val theme = LocalRFTheme.current
    val title = stringResource(
        when (state.page) {
            AgentsPage.LIST -> R.string.agents_shell_title
            AgentsPage.PROFILE -> R.string.agents_profile
            AgentsPage.EDIT -> R.string.agents_edit
        }
    )
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                if (rooted && state.page == AgentsPage.LIST) {
                    KitPageHeader(title, large = true, tag = "agents-header")
                } else if (rooted) {
                    KitPageHeader(title, onBack = { onAction(AgentsAction.Back) }, tag = "agents-header")
                } else {
                    KitPageHeader(
                        title,
                        subtitle = state.account,
                        onBack = { onAction(AgentsAction.Back) },
                        actionLabel = stringResource(R.string.agents_retry),
                        onAction = { if (!state.loading && !state.busy) onAction(AgentsAction.Refresh) }
                    )
                }
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    notices(state, onAction)
                    when (state.page) {
                        AgentsPage.LIST -> if (!state.accountRequired) list(state, onAction)
                        AgentsPage.PROFILE -> state.selected?.let { profile(it, state, onAction) }
                        AgentsPage.EDIT -> edit(state, onAction)
                    }
                }
            }
        }
    }
    state.review?.let { review -> Review(review, state, onAction) }
}

private fun LazyListScope.notices(state: AgentsState, onAction: (AgentsAction) -> Unit) {
    if (state.busy) item("busy", contentType = "notice") { KitEmpty("loader-circle", stringResource(R.string.agents_loading), "") }
    state.notice?.let { item("notice", contentType = "notice") { Notice(it.render(), "agents-notice") } }
    // A failed first read is a centred failure with retry, never an empty directory.
    state.problem?.let { problem ->
        item("problem", contentType = "notice") {
            if (!state.loaded) {
                KitEmpty(
                    "circle-alert",
                    stringResource(R.string.agents_unavailable),
                    problem.render(),
                    action = stringResource(R.string.agents_try_again),
                    onAction = { onAction(AgentsAction.Refresh) },
                    tag = "agents-error"
                )
            } else {
                Notice(problem.render())
            }
        }
    }
    if (state.accountRequired) {
        item("account", contentType = "notice") {
            KitEmpty(
                "user-round",
                stringResource(R.string.agents_account),
                "",
                action = stringResource(R.string.agents_setup),
                onAction = { onAction(AgentsAction.SetupAccount) },
                tag = "agents-account"
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

/** MOB-D01 directory, D01E (no match), D20 (none yet) and D21 (list states). */
private fun LazyListScope.list(state: AgentsState, onAction: (AgentsAction) -> Unit) {
    state.pending?.let { pending ->
        item("pending:" + pending.id, contentType = "pending") {
            Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
                KitApprovalCard(
                    requester = stringResource(R.string.agents_pending_requester),
                    status = pending.state.render(),
                    title = pending.name?.let { uiText(R.string.agents_pending_named, UiText.Untrusted(it)).render() }
                        ?: stringResource(R.string.agents_pending_title),
                    scopeLabel = stringResource(R.string.agents_pending_scope),
                    scopeDetail = stringResource(R.string.agents_pending_blocked),
                    decline = "",
                    approve = "",
                    onDecline = {},
                    onApprove = {},
                    result = pending.expiry?.let { uiText(R.string.agents_pending_expires, it).render() },
                    actions = false,
                    tag = "agents-pending"
                )
            }
        }
    }
    if (state.loaded && state.loadedCount == 0 && state.problem == null) {
        item("empty", contentType = "notice") {
            // Placed as on the board: three parts of the free space above, seven below.
            Column(Modifier.fillParentMaxSize().padding(horizontal = LocalRFTheme.current.gap.dp)) {
                Spacer(Modifier.weight(EMPTY_ABOVE))
                KitEmpty(
                    "bot",
                    stringResource(R.string.agents_empty_title),
                    stringResource(R.string.agents_empty_body),
                    tag = "agents-empty"
                )
                KitTipBar(stringResource(R.string.agents_create_on_desktop), "monitor", tag = "agents-desktop")
                Spacer(Modifier.weight(EMPTY_BELOW))
            }
        }
        return
    }
    item("search", contentType = "search") {
        Box(Modifier.padding(horizontal = LocalRFTheme.current.gap.dp)) {
            KitSearchField(state.query, { onAction(AgentsAction.Query(it)) }, stringResource(R.string.agents_search), tag = "agents-search")
        }
    }
    if (state.loading && !state.loaded) {
        items(SKELETON_ROWS, key = { "skeleton:$it" }, contentType = { "skeleton" }) { KitSkeletonRow(it, tag = "agents-skeleton") }
        return
    }
    if (state.loaded && state.rows.isEmpty() && state.query.isNotBlank()) {
        item("no-match", contentType = "notice") {
            KitEmpty(
                "search-x",
                stringResource(R.string.agents_no_match),
                stringResource(R.string.agents_no_match_body),
                tag = "agents-no-match"
            )
        }
    }
    if (state.query.isNotBlank() && (state.hasMore || state.hasBefore)) {
        item("loaded-only", contentType = "notice") { Notice(stringResource(R.string.agents_filter_loaded_only)) }
    }
    if (state.hasBefore) {
        item("before", contentType = "more") {
            // Rows before the window were dropped to bound memory; reaching the start reads them again.
            LaunchedEffect(state.rows.firstOrNull()?.id) { onAction(AgentsAction.More(forward = false)) }
            KitSkeletonRow(0)
        }
    }
    items(state.rows, key = { "agent:" + it.id }, contentType = { "agent" }) { row ->
        KitRow(
            row.name,
            { onAction(AgentsAction.Select(row.id)) },
            value = uiText(R.string.agents_row_value, UiText.Untrusted(row.responsibility), row.status).render(),
            leading = { KitAvatar("", AVATAR, KitAvatarKind.Agent, icon = row.icon) },
            trailing = KitRowTrailing.None,
            divider = false,
            peer = true,
            testTag = "agent:" + row.id
        )
    }
    if (state.hasMore) {
        item("more", contentType = "more") {
            LaunchedEffect(state.rows.lastOrNull()?.id) { onAction(AgentsAction.More()) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(
                    enabled = !state.loadingMore,
                    onClick = { onAction(AgentsAction.More()) },
                    modifier = Modifier.testTag("agents-more")
                ) { Text(stringResource(R.string.agents_load_more)) }
            }
        }
    }
    if (state.loaded) item("desktop", contentType = "notice") { Notice(stringResource(R.string.agents_create_on_desktop)) }
}

/** MOB-D03: identity, the read-only access policy, and the actions this phone can take. */
private fun LazyListScope.profile(profile: AgentProfile, state: AgentsState, onAction: (AgentsAction) -> Unit) {
    item("identity", contentType = "identity") {
        val theme = LocalRFTheme.current
        Row(Modifier.fillMaxWidth().padding(horizontal = theme.gap.dp), verticalAlignment = Alignment.CenterVertically) {
            KitAvatar("", PROFILE_AVATAR, KitAvatarKind.Agent, icon = profile.row.icon)
            Column(Modifier.padding(start = theme.gap.dp).weight(1f)) {
                UntrustedText(profile.row.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("agent-name"))
                Text(profile.row.status.render(), color = theme.secondary, modifier = Modifier.testTag("agent-status"))
            }
        }
    }
    item("responsibility", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp / 2)) {
            Text(stringResource(R.string.agents_responsibility), color = theme.secondary)
            UntrustedText(profile.row.responsibility, kind = UntrustedKind.Excerpt)
        }
    }
    item("access", contentType = "rows") {
        Column {
            KitRow(stringResource(R.string.agents_role), {}, value = profile.role.render(), trailing = KitRowTrailing.None, enabled = true)
            KitRow(stringResource(R.string.agents_access_dm), {}, value = profile.directMessages.render(), trailing = KitRowTrailing.None)
            KitRow(
                stringResource(R.string.agents_access_groups),
                {},
                value = profile.groupInvitations.render(),
                trailing = KitRowTrailing.None
            )
            Notice(stringResource(R.string.agents_access_read_only))
        }
    }
    if (profile.row.active) {
        item("actions", contentType = "body") {
            val theme = LocalRFTheme.current
            Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitButton(
                    stringResource(R.string.agents_message),
                    { onAction(AgentsAction.Message) },
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    tag = "agent-message"
                )
                KitButton(
                    stringResource(R.string.agents_edit),
                    { onAction(AgentsAction.Edit) },
                    Modifier.fillMaxWidth(),
                    KitButtonKind.Secondary,
                    enabled = !state.busy,
                    tag = "agent-edit"
                )
                KitButton(
                    stringResource(R.string.agents_delete),
                    { onAction(AgentsAction.Delete) },
                    Modifier.fillMaxWidth(),
                    KitButtonKind.Destructive,
                    enabled = !state.busy,
                    tag = "agent-delete"
                )
                Notice(stringResource(R.string.agents_desktop_only_controls))
            }
        }
    }
}

/** MOB-D10: name and responsibility; the avatar is kept as stored. */
private fun LazyListScope.edit(state: AgentsState, onAction: (AgentsAction) -> Unit) {
    item("edit", contentType = "body") {
        val theme = LocalRFTheme.current
        Column(Modifier.padding(horizontal = theme.gap.dp), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            KitTextField(
                state.name,
                { onAction(AgentsAction.Name(it)) },
                stringResource(R.string.agents_name),
                error = state.nameIssue?.render(),
                enabled = !state.busy,
                tag = "agent-edit-name"
            )
            KitTextField(
                state.responsibility,
                { onAction(AgentsAction.Responsibility(it)) },
                stringResource(R.string.agents_responsibility),
                error = state.responsibilityIssue?.render(),
                enabled = !state.busy,
                tag = "agent-edit-responsibility"
            )
            Notice(stringResource(R.string.agents_edit_sync))
            KitButton(
                stringResource(R.string.agents_save),
                { onAction(AgentsAction.Save) },
                Modifier.fillMaxWidth(),
                enabled = !state.busy && state.name.isNotBlank() && state.responsibility.isNotBlank(),
                tag = "agent-save"
            )
        }
    }
}

@Composable
private fun Review(review: AgentReview, state: AgentsState, onAction: (AgentsAction) -> Unit) {
    val theme = LocalRFTheme.current
    HostConfirmationDialog(onDismissRequest = { onAction(AgentsAction.Deny) }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            Text(stringResource(R.string.agents_review), color = theme.text)
            Text(review.operation.render())
            UntrustedText(review.target)
            Text(review.explanation.render(), color = theme.secondary)
            Text(stringResource(R.string.agents_review_account, review.account), color = theme.secondary)
            HostConfirmationActions(
                review,
                stringResource(R.string.agents_deny),
                stringResource(R.string.agents_confirm),
                stringResource(R.string.agents_confirm_accessibility, review.operation.render()),
                enabled = !state.busy,
                onDeny = { onAction(AgentsAction.Deny) },
                onConfirm = { onAction(AgentsAction.Confirm) }
            )
        }
    }
}

private const val SKELETON_ROWS = 4
private const val EMPTY_ABOVE = 3f
private const val EMPTY_BELOW = 7f

/** MOB-D01 agent avatar (44 dp). */
private const val AVATAR = 44f
private const val PROFILE_AVATAR = 56f
