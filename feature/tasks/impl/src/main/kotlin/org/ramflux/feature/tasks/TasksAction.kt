// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import org.ramflux.data.TaskDecision
sealed interface TasksAction {
    data class Visible(val value: Boolean) : TasksAction
    data class Conversation(val id: String) : TasksAction
    data class Select(val id: String) : TasksAction
    data class Permission(val id: String) : TasksAction
    data class Prepare(val decision: TaskDecision) : TasksAction

    /** Approve or deny the pending approval [id]: re-read first, then the host confirmation. */
    data class ReviewApproval(val id: String, val approve: Boolean) : TasksAction
    data class Confirm(val operationId: String) : TasksAction
    data class Reconcile(val operationId: String) : TasksAction
    data object Deny : TasksAction
    data object Conversations : TasksAction
    data object Permissions : TasksAction
    data object Refresh : TasksAction
    data class More(val forward: Boolean = true) : TasksAction
    data object Back : TasksAction
    data object SetupAccount : TasksAction
    data object Unavailable : TasksAction
}
