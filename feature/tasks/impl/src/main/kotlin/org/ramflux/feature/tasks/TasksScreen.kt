// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
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
import org.ramflux.data.TaskDecision
import org.ramflux.designsystem.*
import org.ramflux.feature.tasks.impl.R
import org.ramflux.generated.*
@Composable
fun TasksScreen(state: TasksState, onAction: (TasksAction) -> Unit) {
    val theme = LocalRFTheme.current
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = theme.column_max.dp).fillMaxSize()) {
                RFPageHeader(
                    stringResource(R.string.tasks_title),
                    state.conversation ?: "",
                    theme,
                    leading = { TextButton(onClick = { onAction(TasksAction.Back) }) { Text(stringResource(R.string.tasks_back)) } },
                    trailing = {
                        TextButton(enabled = !state.loading && !state.busy, onClick = {
                            onAction(TasksAction.Refresh)
                        }) { Text(stringResource(R.string.tasks_refresh)) }
                    }
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                    verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
                ) {
                    if (state.loading || state.busy) {
                        item("loading") {
                            RFStateBlock(stringResource(R.string.tasks_loading), "", RFStateKind.Loading, theme, {})
                        }
                    }
                    if (!state.fresh) item("stale") { Text(stringResource(R.string.tasks_stale)) }
                    state.problem?.let {
                        item(
                            "error"
                        ) { RFStateBlock(stringResource(R.string.tasks_error), it.render(), RFStateKind.Error, theme, {}) }
                    }
                    state.notice?.let { item("notice") { Text(it.render()) } }
                    if (state.unavailable) {
                        item("unavailable") {
                            RFStateBlock(
                                stringResource(R.string.tasks_unavailable),
                                stringResource(R.string.tasks_unavailable_detail),
                                RFStateKind.Empty,
                                theme,
                                {}
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
                    when (state.page) {
                        TasksPage.CONVERSATIONS -> {
                            item("context") { Text(stringResource(R.string.tasks_context)) }
                            if (state.loaded && state.contexts.isEmpty()) item("empty") { Text(stringResource(R.string.tasks_no_contexts)) }
                            items(state.contexts, key = { it.id }) { row ->
                                RFListRow(row.id, row.title, "", theme, false, {}, {}, { onAction(TasksAction.Conversation(row.id)) })
                            }
                            item(
                                "create"
                            ) {
                                TextButton(
                                    onClick = { onAction(TasksAction.Unavailable) }
                                ) { Text(stringResource(R.string.tasks_create)) }
                            }
                        }
                        TasksPage.LIST -> {
                            if (state.loaded && state.rows.isEmpty()) item("empty") { Text(stringResource(R.string.tasks_empty)) }
                            items(state.rows, key = { it.id }) { row ->
                                RFListRow(row.id, row.title, row.status.render(), theme, false, {
                                }, { Text(UiText.Unsigned(row.version).render()) }, { onAction(TasksAction.Select(row.id)) })
                            }
                        }
                        TasksPage.DETAIL -> state.selected?.let { row ->
                            item("title") {
                                Text(row.title, style = MaterialTheme.typography.headlineSmall)
                                Text(row.status.render())
                            }
                            item(
                                "facts"
                            ) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)
                                ) { for (fact in row.facts) Text(fact.render()) }
                            }
                            if (row.stalled) item("stalled") { Text(stringResource(R.string.tasks_stalled)) }
                            if (row.pendingResolution) item("resolution") { Text(stringResource(R.string.tasks_pending_resolution)) }
                            items(row.steps, key = { "step:" + it.id }) { step ->
                                RFListRow(
                                    step.id,
                                    step.title,
                                    step.status.render(),
                                    theme,
                                    false,
                                    {},
                                    {},
                                    { onAction(TasksAction.Unavailable) }
                                )
                                if (step.intervention) Text(stringResource(R.string.tasks_intervention))
                            }
                            row.result?.let { item("result") { Text(it) } }
                            item("boundary") { Text(stringResource(R.string.tasks_operation_boundary)) }
                        }
                        TasksPage.PERMISSIONS -> {
                            item("readonly") { Text(stringResource(R.string.tasks_readonly)) }
                            if (state.loaded && state.permissions.isEmpty()) {
                                item(
                                    "empty"
                                ) { Text(stringResource(R.string.tasks_no_permissions)) }
                            }
                            items(state.permissions, key = { it.id }) { row ->
                                RFListRow(
                                    row.id,
                                    row.title ?: row.kind.render(),
                                    row.kind.render(),
                                    theme,
                                    false,
                                    {},
                                    {},
                                    { onAction(TasksAction.Permission(row.id)) }
                                )
                            }
                        }
                        TasksPage.PERMISSION -> state.selectedPermission?.let { row ->
                            item("title") {
                                Text(row.title ?: row.kind.render())
                                Text(row.kind.render())
                                Text(stringResource(R.string.tasks_readonly))
                            }
                            items(row.facts.indices.toList(), key = { "permission-fact:$it" }) { index -> Text(row.facts[index].render()) }
                        }
                    }
                    if (state.canMore) {
                        item("more") {
                            TextButton(enabled = !state.loading && !state.busy, onClick = {
                                onAction(TasksAction.More)
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
                    TextButton(onClick = {
                        onAction(TasksAction.Unavailable)
                    }) { Text(stringResource(R.string.tasks_review_step)) }
                }
            }
        }
    }
    state.confirmation?.let { facts ->
        Dialog(onDismissRequest = { onAction(TasksAction.Deny) }) {
            RFDialogShell(stringResource(R.string.tasks_review), facts.operation.render(), theme, content = {
                LazyColumn(Modifier.heightIn(max = (theme.gap * 20).dp)) {
                    items(facts.facts.indices.toList()) { index -> Text(facts.facts[index].render()) }
                }
            }, actions = {
                TextButton(onClick = { onAction(TasksAction.Deny) }) { Text(stringResource(R.string.tasks_deny)) }
                Button(enabled = state.fresh && !state.busy && !state.loading, onClick = {
                    onAction(TasksAction.Confirm(facts.id))
                }) { Text(stringResource(R.string.tasks_confirm)) }
            })
        }
    }
}
