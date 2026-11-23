// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
@Immutable enum class TasksPage { CONVERSATIONS, LIST, DETAIL, PERMISSIONS, PERMISSION }

@Immutable data class TaskContextRow(val id: String, val title: String)

@Immutable data class TaskStepRow(val id: String, val title: String, val status: UiText, val intervention: Boolean)

@Immutable data class TaskRow(
    val id: String,
    val title: String,
    val status: UiText,
    val version: ULong,
    val facts: ImmutableList<UiText>,
    val steps: ImmutableList<TaskStepRow>,
    val result: String?,
    val canDecide: Boolean,
    val canCancel: Boolean,
    val pendingResolution: Boolean,
    val stalled: Boolean
) {
    override fun toString() = "TaskRow(<redacted>)"
}

/**
 * A grant, approval or receipt. A pending approval ([decidable]) is shown as an approval card; [approvable] is false
 * for a `remote_app` approval, which the phone can only deny.
 */
@Immutable data class TaskPermissionRow(
    val id: String,
    val title: String?,
    val kind: UiText,
    val facts: ImmutableList<UiText>,
    val requester: String? = null,
    val scope: String? = null,
    val decidable: Boolean = false,
    val approvable: Boolean = false
)

@Immutable data class TaskConfirmation(val id: String, val operation: UiText, val facts: ImmutableList<UiText>)

@Immutable data class TasksState(
    val page: TasksPage = TasksPage.CONVERSATIONS,
    val conversation: String? = null,
    val contexts: ImmutableList<TaskContextRow> = persistentListOf(),
    val rows: ImmutableList<TaskRow> = persistentListOf(),
    val selected: TaskRow? = null,
    val permissions: ImmutableList<TaskPermissionRow> = persistentListOf(),
    val selectedPermission: TaskPermissionRow? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val fresh: Boolean = false,
    val accountRequired: Boolean = false,
    /** More rows after the loaded window. */
    val canMore: Boolean = false,
    /** Rows before the loaded window (dropped from the bounded window or not yet read). */
    val hasBefore: Boolean = false,
    val loadingMore: Boolean = false,
    val problem: UiText? = null,
    val unavailable: Boolean = false,
    val notice: UiText? = null,
    val pendingId: String? = null,
    val pendingFacts: ImmutableList<UiText> = persistentListOf(),
    val confirmation: TaskConfirmation? = null
) {
    override fun toString() = "TasksState(<redacted>)"
}
