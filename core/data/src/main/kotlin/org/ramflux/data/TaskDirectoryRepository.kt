// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.Flow

data class TaskStep(val id: String, val title: String, val state: String, val intervention: String?)
data class TaskRecord(val id: String, val conversation: String, val version: ULong, val title: String, val state: String,
    val requester: String?, val assignee: String, val steps: List<TaskStep>, val result: String?, val pendingResolution: Boolean, val stalled: Boolean) {
    val decidable get() = state == "proposed" && !pendingResolution && version < ULong.MAX_VALUE
    val cancellable get() = state in setOf("proposed", "running", "needs_me") && !pendingResolution
    override fun toString() = "TaskRecord(<redacted>)"
}
/** A conversation that may carry tasks: the task list is read per conversation. */
data class TaskContext(val id: String, val title: String)
enum class TaskDecision { ACCEPT, DECLINE, CANCEL }
data class TaskAttempt(val operationId: String, val expected: TaskRecord, val decision: TaskDecision)
data class TaskOutcome(val id: String, val pending: Boolean, val accepted: Boolean = true)
enum class TaskPermissionKind { GRANT, APPROVAL, RECEIPT }
data class TaskPermission(val id: String, val reference: String, val kind: TaskPermissionKind, val server: String?, val tool: String?,
    val status: String?, val expires: Long, val scope: String?, val revoked: Boolean?, val fullDelegation: Boolean?, val capabilities: List<String>,
    val registryHash: String?, val manifestHash: String?, val argumentsHash: String?, val decision: String?, val grant: String?,
    val conversation: String?, val task: String?, val step: String?, val openingRevision: ULong?, val requester: String?, val assignee: String?,
    val executor: String?, val delivery: String?, val reason: String?,
    /** An approval's confirmation mode: `attended_local`, `remote_app` or `task_requester`. */
    val confirmation: String? = null) {
    /** A pending approval this phone may answer. */
    val decidable get() = kind == TaskPermissionKind.APPROVAL && status in setOf("pending", "approval_required")

    /**
     * A `remote_app` approval needs a grant signed by the app (`mcp.approval.granted` with a signature,
     * `RemoteAppApprovalRequired` otherwise); the C ABI exposes no app signing, so the phone can only deny it.
     */
    val approvable get() = decidable && confirmation != REMOTE_APP
    override fun toString() = "TaskPermission(<redacted>)"
    companion object { const val REMOTE_APP = "remote_app" }
}

/** Event topics the task screens follow; never `*`. */
enum class TaskTopic(val sdk: String) {
    CONVERSATIONS("conversation.changed"),
    TASKS("task.changed"),
    APPROVAL_REQUESTED("mcp.approval.request"),
    APPROVAL_GRANTED("mcp.approval.granted"),
    APPROVAL_DENIED("mcp.approval.denied")
}
/**
 * Tasks and approvals of one account. Every list is a windowed source: a null cursor starts a fresh listing and
 * each page carries the cursors before and after it.
 */
interface TaskDirectoryRepository {
    /** Conversations that may carry tasks (`conversation.directory`, hidden rows left out). */
    suspend fun contextPage(account: String, cursor: String?): ClientResult<WindowedPage<TaskContext>>

    /** Tasks of [conversation] (`task.list`). */
    suspend fun taskPage(account: String, conversation: String, cursor: String?): ClientResult<WindowedPage<TaskRecord>>
    suspend fun detail(account: String, conversation: String, task: String): ClientResult<TaskRecord>

    /** Grants, approvals and approval receipts (`grant.list`, unpaged in the SDK: served from one snapshot). */
    suspend fun permissionPage(account: String, cursor: String?): ClientResult<WindowedPage<TaskPermission>>

    /** A fresh read of one permission row by its reference, bypassing any snapshot. */
    suspend fun permission(account: String, kind: TaskPermissionKind, reference: String): ClientResult<TaskPermission>

    /**
     * Approves or denies a pending approval (`grant.approve` / `grant.deny`) after the host confirmation. The data
     * layer re-reads the approval first and refuses when it is no longer exactly [expected], no longer pending, or
     * is a `remote_app` approval asked to be approved.
     */
    suspend fun decideApproval(account: String, expected: TaskPermission, approve: Boolean): ClientResult<Unit>
    suspend fun mutate(account: String, attempt: TaskAttempt): ClientResult<TaskOutcome>
    fun pending(account: String): TaskAttempt?
    suspend fun contextualAvailability(): ClientResult<Unit>

    /** One emission per change on [topics] (or a resync); collect while the screen is visible. */
    fun updates(account: String, topics: Set<TaskTopic>): Flow<ClientResult<Unit>>
}
