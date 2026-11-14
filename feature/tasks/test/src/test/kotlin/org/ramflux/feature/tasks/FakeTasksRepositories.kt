// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
fun taskRecord(version: ULong = 7u, state: String = "proposed") = TaskRecord(
    "task", "c", version, "Actual task", state, "human", "agent",
    listOf(TaskStep("step", "Actual step", "needs_me", "approval")), null, false, false
)
class FakeTasks : TaskDirectoryRepository {
    var reads = 0
    var paging: suspend (String?) -> ClientResult<TaskPage> = { ClientResult.Ready(TaskPage(listOf(taskRecord()), null)) }
    var detailing: suspend () -> ClientResult<TaskRecord> = { ClientResult.Ready(taskRecord()) }
    var permissionRows: ClientResult<List<TaskPermission>> = ClientResult.Ready(emptyList())
    val mutations = mutableListOf<TaskAttempt>()
    var retained: TaskAttempt? = null
    var mutating: suspend (TaskAttempt) -> ClientResult<TaskOutcome> = { ClientResult.Ready(TaskOutcome(it.expected.id, false)) }
    val invalidations = MutableSharedFlow<ClientResult<Unit>>(extraBufferCapacity = 8)
    override suspend fun page(account: String, conversation: String, cursor: String?): ClientResult<TaskPage> {
        reads++
        return paging(cursor)
    }
    override suspend fun detail(account: String, conversation: String, task: String): ClientResult<TaskRecord> {
        reads++
        return detailing()
    }
    override suspend fun permissions(account: String) = permissionRows
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
    override fun updates(account: String) = invalidations
}
class FakeTaskContexts : ConversationDirectoryRepository {
    var value: ClientResult<ConversationPage> = ClientResult.Ready(
        ConversationPage(listOf(ConversationSummary("c", "Actual conversation", 0u, false, false)), null)
    )
    override suspend fun page(account: String, cursor: String?) = value
    override suspend fun searchAvailability(): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONVERSATIONS)
    override suspend fun openAvailability(account: String, conversation: String): ClientResult<Unit> = ClientResult.Ready(Unit)
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
