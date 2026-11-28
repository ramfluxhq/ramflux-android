// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.data.TaskDecision
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.tasks.impl.R

@Composable
fun TasksScreen(state: TasksState, onAction: (TasksAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val subtitle = state.conversation?.takeIf { it.isNotEmpty() }
    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize()) {
                KitPageHeader(
                    stringResource(R.string.tasks_title),
                    subtitle = subtitle,
                    onBack = { onAction(TasksAction.Back) }
                )
                if (subtitle != null) Text(subtitle, color = theme.secondary)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            KitEmpty("loader", stringResource(R.string.tasks_loading), "")
                        }
                    }
                    if (!state.fresh) item("stale", contentType = "notice") { Text(stringResource(R.string.tasks_stale)) }
                    state.problem?.let {
                        item("error") { KitEmpty("circle-alert", stringResource(R.string.tasks_error), it.render()) }
                    }
                    state.notice?.let { item("notice") { Text(it.render()) } }
                    if (state.unavailable) {
                        item("unavailable") {
                            KitEmpty(
                                "inbox",
                                stringResource(R.string.tasks_unavailable),
                                stringResource(R.string.tasks_unavailable_detail)
                            )
                        }
                    }
                    if (state.accountRequired) {
                        item("account") {
                            Text(stringResource(R.string.tasks_account_required))
                            TextButton(onClick = { onAction(TasksAction.SetupAccount) }) { Text(stringResource(R.string.tasks_setup)) }
                        }
                    }
                    state.pendingId?.let { id ->
                        item("pending") {
                            Text(stringResource(R.string.tasks_pending, id))
                            for (fact in state.pendingFacts) Text(fact.render())
                            TextButton(enabled = state.fresh && !state.busy && !state.loading, onClick = {
                                onAction(TasksAction.Reconcile(id))
                            }) { Text(stringResource(R.string.tasks_reconcile)) }
                        }
                    }
                    if (state.hasBefore && state.page in WINDOWED) {
                        item("before", contentType = "more") {
                            LaunchedEffect(
                                state.page,
                                state.contexts.firstOrNull()?.id,
                                state.rows.firstOrNull()?.id,
                                state.permissions.firstOrNull()?.id
                            ) {
                                onAction(TasksAction.More(forward = false))
                            }
                            TextButton(enabled = !state.loadingMore && !state.busy, onClick = {
                                onAction(TasksAction.More(forward = false))
                            }) { Text(stringResource(R.string.tasks_more_before)) }
                        }
                    }
                    when (state.page) {
                        TasksPage.CONVERSATIONS -> {
                            item("context", contentType = "notice") { Text(stringResource(R.string.tasks_context)) }
                            if (state.loaded && state.contexts.isEmpty()) {
                                item("empty", contentType = "notice") { Text(stringResource(R.string.tasks_no_contexts)) }
                            }
                            items(state.contexts, key = { "context:" + it.id }, contentType = { "context" }) { row ->
                                KitRow(
                                    row.title,
                                    { onAction(TasksAction.Conversation(row.id)) },
                                    trailing = KitRowTrailing.None,
                                    testTag = row.id
                                )
                            }
                            item("create", contentType = "notice") {
                                TextButton(onClick = { onAction(TasksAction.Unavailable) }) { Text(stringResource(R.string.tasks_create)) }
                            }
                        }
                        TasksPage.LIST -> {
                            if (state.loaded && state.rows.isEmpty()) {
                                item("empty", contentType = "notice") { Text(stringResource(R.string.tasks_empty)) }
                            }
                            items(state.rows, key = { "task:" + it.id }, contentType = { "task" }) { row ->
                                KitRow(
                                    row.title,
                                    { onAction(TasksAction.Select(row.id)) },
                                    value = row.status.render(),
                                    trailingContent = { Text(UiText.Unsigned(row.version).render()) },
                                    testTag = row.id
                                )
                            }
                        }
                        TasksPage.DETAIL -> state.selected?.let { row ->
                            item("title", contentType = "title") {
                                Text(row.title, style = MaterialTheme.typography.headlineSmall)
                                Text(row.status.render())
                            }
                            item("facts", contentType = "facts") {
                                Column(verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
                                    for (fact in row.facts) Text(fact.render())
                                }
                            }
                            if (row.stalled) item("stalled", contentType = "notice") { Text(stringResource(R.string.tasks_stalled)) }
                            if (row.pendingResolution) {
                                item("resolution", contentType = "notice") { Text(stringResource(R.string.tasks_pending_resolution)) }
                            }
                            if (row.steps.isNotEmpty()) {
                                item("steps", contentType = "header") {
                                    Text(stringResource(R.string.tasks_steps), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            items(row.steps, key = { "step:" + it.id }, contentType = { "step" }) { step ->
                                KitRow(
                                    step.title,
                                    { onAction(TasksAction.Unavailable) },
                                    value = step.status.render(),
                                    trailing = KitRowTrailing.None,
                                    testTag = step.id
                                )
                                if (step.intervention) Text(stringResource(R.string.tasks_intervention))
                            }
                            row.result?.let { item("result", contentType = "notice") { Text(it) } }
                            item("boundary", contentType = "notice") { Text(stringResource(R.string.tasks_operation_boundary)) }
                        }
                        TasksPage.PERMISSIONS -> {
                            if (state.loaded && state.permissions.isEmpty()) {
                                item("empty", contentType = "notice") { Text(stringResource(R.string.tasks_no_permissions)) }
                            }
                            val pending = state.permissions.filter { it.decidable }
                            if (pending.isNotEmpty()) {
                                item("pending-approvals", contentType = "header") {
                                    Text(stringResource(R.string.tasks_pending_approvals), style = MaterialTheme.typography.titleSmall)
                                }
                            }
                            items(pending, key = { "approval:" + it.id }, contentType = { "approval" }) { row ->
                                ApprovalCard(row, enabled = state.fresh && !state.busy && !state.loading, onAction)
                            }
                            if (state.permissions.size > pending.size) {
                                item("records", contentType = "header") {
                                    Text(stringResource(R.string.tasks_records), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(R.string.tasks_readonly))
                                }
                            }
                            items(
                                state.permissions.filterNot { it.decidable },
                                key = { "permission:" + it.id },
                                contentType = { "permission" }
                            ) { row ->
                                KitRow(
                                    row.title ?: row.kind.render(),
                                    { onAction(TasksAction.Permission(row.id)) },
                                    value = row.kind.render(),
                                    trailing = KitRowTrailing.None,
                                    testTag = row.id
                                )
                            }
                        }
                        TasksPage.PERMISSION -> state.selectedPermission?.let { row ->
                            item("title", contentType = "title") {
                                if (row.decidable) {
                                    ApprovalCard(row, enabled = state.fresh && !state.busy && !state.loading, onAction)
                                } else {
                                    Text(row.title ?: row.kind.render())
                                    Text(row.kind.render())
                                    Text(stringResource(R.string.tasks_readonly))
                                }
                            }
                            items(row.facts.indices.toList(), key = { "permission-fact:" + it }, contentType = { "fact" }) { index ->
                                Text(row.facts[index].render())
                            }
                        }
                    }
                    if (state.canMore && state.page in WINDOWED) {
                        item("more", contentType = "more") {
                            LaunchedEffect(
                                state.page,
                                state.contexts.lastOrNull()?.id,
                                state.rows.lastOrNull()?.id,
                                state.permissions.lastOrNull()?.id
                            ) {
                                onAction(TasksAction.More())
                            }
                            TextButton(enabled = !state.loadingMore && !state.busy, onClick = {
                                onAction(TasksAction.More())
                            }) { Text(stringResource(R.string.tasks_more)) }
                        }
                    }
                    item("navigation") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onAction(TasksAction.Conversations) }
                            ) { Text(stringResource(R.string.tasks_conversations)) }
                            TextButton(
                                enabled = !state.busy,
                                onClick = { onAction(TasksAction.Permissions) }
                            ) { Text(stringResource(R.string.tasks_permissions)) }
                        }
                    }
                }
                val enabled = state.fresh && !state.loading && !state.busy && state.pendingId == null
                if (state.page == TasksPage.DETAIL) {
                    state.selected?.let { row ->
                        FlowRow(Modifier.fillMaxWidth().padding(theme.gap.dp), horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                            if (row.canDecide) {
                                OutlinedButton(enabled = enabled, onClick = {
                                    onAction(TasksAction.Prepare(TaskDecision.DECLINE))
                                }) { Text(stringResource(R.string.tasks_decline)) }
                                Button(
                                    enabled = enabled,
                                    onClick = { onAction(TasksAction.Prepare(TaskDecision.ACCEPT)) }
                                ) { Text(stringResource(R.string.tasks_accept)) }
                            } else if (row.canCancel) {
                                OutlinedButton(enabled = enabled, onClick = {
                                    onAction(TasksAction.Prepare(TaskDecision.CANCEL))
                                }) { Text(stringResource(R.string.tasks_cancel)) }
                            }
                            Button(
                                enabled = !state.busy,
                                onClick = { onAction(TasksAction.Unavailable) }
                            ) { Text(stringResource(R.string.tasks_review_step)) }
                        }
                    }
                }
                if (state.page == TasksPage.PERMISSION) {
                    TextButton(onClick = { onAction(TasksAction.Unavailable) }) { Text(stringResource(R.string.tasks_review_step)) }
                }
            }
        }
    }
    state.confirmation?.let { facts ->
        HostConfirmationDialog(onDismissRequest = { onAction(TasksAction.Deny) }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                Text(stringResource(R.string.tasks_review), color = theme.text)
                Text(facts.operation.render())
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(facts.facts.indices.toList()) { index -> Text(facts.facts[index].render()) }
                }
                HostConfirmationActions(
                    facts,
                    stringResource(R.string.tasks_deny),
                    stringResource(R.string.tasks_confirm),
                    stringResource(R.string.tasks_confirm_accessibility, facts.operation.render()),
                    enabled = state.fresh && !state.busy && !state.loading,
                    onDeny = { onAction(TasksAction.Deny) },
                    onConfirm = { onAction(TasksAction.Confirm(facts.id)) }
                )
            }
        }
    }
}

private val WINDOWED = setOf(TasksPage.CONVERSATIONS, TasksPage.LIST, TasksPage.PERMISSIONS)

/**
 * A pending approval as the Kit approval card. A `remote_app` request has no approve button: the card says that only
 * the requesting app can approve it and offers deny alone.
 */
@Composable
private fun ApprovalCard(row: TaskPermissionRow, enabled: Boolean, onAction: (TasksAction) -> Unit) {
    val theme = LocalRFTheme.current
    // The deny-only button takes the card's width (the card caps its own width).
    Column(
        if (row.approvable) Modifier else Modifier.width(IntrinsicSize.Max),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitApprovalCard(
            requester = row.requester?.let { stringResource(R.string.tasks_approval_requester, it) } ?: row.kind.render(),
            status = stringResource(R.string.tasks_pending_status),
            title = row.title ?: row.kind.render(),
            scopeLabel = stringResource(R.string.tasks_approval_scope),
            scopeDetail = row.scope.orEmpty(),
            decline = stringResource(R.string.tasks_deny),
            approve = stringResource(R.string.tasks_approve),
            onDecline = { if (enabled) onAction(TasksAction.ReviewApproval(row.id, approve = false)) },
            onApprove = { if (enabled) onAction(TasksAction.ReviewApproval(row.id, approve = true)) },
            result = if (row.approvable) null else stringResource(R.string.tasks_approval_remote_only),
            actions = row.approvable,
            tag = "approval:" + row.id
        )
        if (!row.approvable) {
            KitButton(
                stringResource(R.string.tasks_deny),
                { onAction(TasksAction.ReviewApproval(row.id, approve = false)) },
                Modifier.fillMaxWidth(),
                enabled = enabled,
                kind = KitButtonKind.Secondary,
                tag = "approval-deny:" + row.id
            )
        }
    }
}
