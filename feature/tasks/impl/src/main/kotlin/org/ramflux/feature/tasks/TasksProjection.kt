// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import kotlinx.collections.immutable.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.tasks.impl.R
internal fun String.taskStatus() = uiText(
    when (this) {
        "proposed" -> R.string.tasks_proposed
        "running" -> R.string.tasks_running
        "needs_me" -> R.string.tasks_needs_me
        "done" -> R.string.tasks_done
        "failed" -> R.string.tasks_failed
        "cancelled" -> R.string.tasks_cancelled
        else -> R.string.tasks_unknown_state
    }
)
internal fun TaskDecision.label() = uiText(
    when (this) {
        TaskDecision.ACCEPT -> R.string.tasks_accept
        TaskDecision.DECLINE -> R.string.tasks_decline
        TaskDecision.CANCEL -> R.string.tasks_cancel
    }
)
internal fun TaskRecord.row() = TaskRow(
    id, title, state.taskStatus(), version,
    buildList {
        add(uiText(R.string.tasks_conversation, UiText.Data(conversation)))
        add(uiText(R.string.tasks_task, UiText.Data(id)))
        add(uiText(R.string.tasks_version, UiText.Unsigned(version)))
        requester?.let { add(uiText(R.string.tasks_requester, UiText.Data(it))) }
        add(uiText(R.string.tasks_assignee, UiText.Data(assignee)))
    }.toPersistentList(),
    steps.map { TaskStepRow(it.id, it.title, it.state.taskStatus(), it.intervention != null) }.toPersistentList(),
    result, decidable, cancellable, pendingResolution, stalled
)
internal fun TaskAttempt.facts(account: String) = (
    listOf(uiText(R.string.tasks_account, UiText.Data(account)), UiText.Data(expected.title)) +
        expected.row().facts + uiText(R.string.tasks_operation, UiText.Data(operationId))
    ).toPersistentList()
internal fun TaskPermission.row(): TaskPermissionRow {
    val kind =
        uiText(
            when (kind) {
                TaskPermissionKind.GRANT -> R.string.tasks_grant
                TaskPermissionKind.APPROVAL -> R.string.tasks_approval
                TaskPermissionKind.RECEIPT -> R.string.tasks_receipt
            }
        )
    val facts = buildList<UiText> {
        fun field(resource: Int, value: String?) {
            value?.let { add(uiText(resource, UiText.Data(it))) }
        }
        add(uiText(R.string.tasks_reference, UiText.Data(reference)))
        field(R.string.tasks_server, server)
        field(R.string.tasks_tool, tool)
        field(R.string.tasks_scope, scope)
        status?.let { add(uiText(R.string.tasks_recorded_status, it.permissionStatus())) }
        field(R.string.tasks_registry, registryHash)
        field(R.string.tasks_manifest, manifestHash)
        field(R.string.tasks_arguments, argumentsHash)
        capabilities.forEach { field(R.string.tasks_capability, it) }
        fullDelegation?.let { add(uiText(if (it) R.string.tasks_full else R.string.tasks_limited)) }
        revoked?.let { add(uiText(if (it) R.string.tasks_revoked else R.string.tasks_not_revoked)) }
        field(R.string.tasks_decision, decision)
        field(R.string.tasks_grant_reference, grant)
        field(R.string.tasks_conversation, conversation)
        field(R.string.tasks_task, task)
        field(R.string.tasks_step, step)
        field(R.string.tasks_requester, requester)
        field(R.string.tasks_assignee, assignee)
        field(R.string.tasks_executor, executor)
        openingRevision?.let { add(uiText(R.string.tasks_version, UiText.Unsigned(it))) }
        delivery?.let { add(uiText(R.string.tasks_delivery, it.permissionStatus())) }
        reason?.let { add(uiText(R.string.tasks_reason, it.permissionReason())) }
        add(uiText(R.string.tasks_expiry, UiText.DateTime(expires)))
    }
    return TaskPermissionRow(id, tool ?: task, kind, facts.toPersistentList())
}

private fun String.permissionStatus() = uiText(
    when (this) {
        "pending", "approval_required" -> R.string.tasks_pending_status
        "approved" -> R.string.tasks_approved
        "denied" -> R.string.tasks_denied
        "submitted" -> R.string.tasks_submitted
        "applied" -> R.string.tasks_applied
        "rejected" -> R.string.tasks_rejected
        "failed" -> R.string.tasks_failed
        "expired" -> R.string.tasks_expired
        "active" -> R.string.tasks_active
        "revoked" -> R.string.tasks_revoked
        else -> R.string.tasks_unknown_state
    }
)
private fun String.permissionReason() = uiText(
    when (this) {
        "retry_exhausted" -> R.string.tasks_retry_exhausted
        "transport_unavailable" -> R.string.tasks_transport_unavailable
        "intervention_closed" -> R.string.tasks_intervention_closed
        "invalid_decision" -> R.string.tasks_invalid_decision
        "submit_conflict" -> R.string.tasks_submit_conflict
        else -> R.string.tasks_unknown_state
    }
)
