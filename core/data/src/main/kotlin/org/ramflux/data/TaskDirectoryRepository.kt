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
data class TaskPage(val rows: List<TaskRecord>, val cursor: String?)
enum class TaskDecision { ACCEPT, DECLINE, CANCEL }
data class TaskAttempt(val operationId: String, val expected: TaskRecord, val decision: TaskDecision)
data class TaskOutcome(val id: String, val pending: Boolean, val accepted: Boolean = true)
enum class TaskPermissionKind { GRANT, APPROVAL, RECEIPT }
data class TaskPermission(val id: String, val reference: String, val kind: TaskPermissionKind, val server: String?, val tool: String?,
    val status: String?, val expires: Long, val scope: String?, val revoked: Boolean?, val fullDelegation: Boolean?, val capabilities: List<String>,
    val registryHash: String?, val manifestHash: String?, val argumentsHash: String?, val decision: String?, val grant: String?,
    val conversation: String?, val task: String?, val step: String?, val openingRevision: ULong?, val requester: String?, val assignee: String?,
    val executor: String?, val delivery: String?, val reason: String?) { override fun toString() = "TaskPermission(<redacted>)" }
interface TaskDirectoryRepository {
    suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<TaskPage>
    suspend fun detail(account: String, conversation: String, task: String): ClientResult<TaskRecord>
    suspend fun permissions(account: String): ClientResult<List<TaskPermission>>
    suspend fun mutate(account: String, attempt: TaskAttempt): ClientResult<TaskOutcome>
    fun pending(account: String): TaskAttempt?
    suspend fun contextualAvailability(): ClientResult<Unit>
    fun updates(account: String): Flow<ClientResult<Unit>>
}
