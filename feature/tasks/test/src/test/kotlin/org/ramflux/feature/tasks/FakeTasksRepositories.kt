// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
fun taskRecord(version: ULong = 7u, state: String = "proposed") = TaskRecord(
    "task", "c", version, "Actual task", state, "human", "agent",
    listOf(TaskStep("step", "Actual step", "needs_me", "approval")), null, false, false
)
fun approvalPermission(id: String = "a1", mode: String = "attended_local", status: String = "pending") = TaskPermission(
    "approval:$id", id, TaskPermissionKind.APPROVAL, "server", "tool", status, 2_000_000_000, "read", null, null, listOf("file.read"),
    null, null, "hash", null, null, null, null, null, null, null, null, null, null, null, mode
)
class FakeTasks : TaskDirectoryRepository {
    var reads = 0
    var paging: suspend (
        String?
    ) -> ClientResult<WindowedPage<TaskRecord>> = { ClientResult.Ready(WindowedPage(listOf(taskRecord()), null, null)) }
    var contexts: suspend (String?) -> ClientResult<WindowedPage<TaskContext>> = {
        ClientResult.Ready(WindowedPage(listOf(TaskContext("c", "Actual conversation")), null, null))
    }
    var detailing: suspend () -> ClientResult<TaskRecord> = { ClientResult.Ready(taskRecord()) }
    var permissionRows: ClientResult<List<TaskPermission>> = ClientResult.Ready(emptyList())
    var permissionPaging: (suspend (String?) -> ClientResult<WindowedPage<TaskPermission>>)? = null
    val mutations = mutableListOf<TaskAttempt>()
    val decisions = mutableListOf<Pair<TaskPermission, Boolean>>()
    var deciding: suspend () -> ClientResult<Unit> = { ClientResult.Ready(Unit) }
    var retained: TaskAttempt? = null
    var mutating: suspend (TaskAttempt) -> ClientResult<TaskOutcome> = { ClientResult.Ready(TaskOutcome(it.expected.id, false)) }
    val invalidations = MutableSharedFlow<ClientResult<Unit>>(extraBufferCapacity = 8)
    val subscriptions = mutableListOf<Set<TaskTopic>>()
    override suspend fun contextPage(account: String, cursor: String?) = contexts(cursor)
    override suspend fun taskPage(account: String, conversation: String, cursor: String?): ClientResult<WindowedPage<TaskRecord>> {
        reads++
        return paging(cursor)
    }
    override suspend fun detail(account: String, conversation: String, task: String): ClientResult<TaskRecord> {
        reads++
        return detailing()
    }
    override suspend fun permissionPage(account: String, cursor: String?): ClientResult<WindowedPage<TaskPermission>> =
        permissionPaging?.invoke(cursor) ?: when (val rows = permissionRows) {
            is ClientResult.Ready -> ClientResult.Ready(WindowedPage(rows.value, null, null))
            is ClientResult.Failed -> rows
            is ClientResult.Unavailable -> rows
        }
    override suspend fun permission(account: String, kind: TaskPermissionKind, reference: String): ClientResult<TaskPermission> =
        when (val rows = permissionRows) {
            is ClientResult.Ready -> rows.value.find { it.kind == kind && it.reference == reference }?.let { ClientResult.Ready(it) }
                ?: ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 18, ClientOutcome.NONE))
            is ClientResult.Failed -> rows
            is ClientResult.Unavailable -> rows
        }
    override suspend fun decideApproval(account: String, expected: TaskPermission, approve: Boolean): ClientResult<Unit> {
        decisions.add(expected to approve)
        return deciding()
    }
    override suspend fun mutate(account: String, attempt: TaskAttempt): ClientResult<TaskOutcome> {
        mutations.add(attempt)
        val result = mutating(attempt)
        if (result is ClientResult.Failed && result.problem.outcome == ClientOutcome.UNKNOWN ||
            result is ClientResult.Ready && result.value.pending
        ) {
            retained = attempt
        } else {
            retained = null
        }
        return result
    }
    override fun pending(account: String) = retained
    override suspend fun contextualAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.TASKS)
    override fun updates(account: String, topics: Set<TaskTopic>): Flow<ClientResult<Unit>> {
        subscriptions.add(topics)
        return invalidations
    }
}
class FakeTaskAccounts : AccountAccessRepository {
    var result: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    override suspend fun list() = result
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeTaskSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
