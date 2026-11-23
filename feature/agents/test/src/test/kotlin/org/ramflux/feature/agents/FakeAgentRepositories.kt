// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import kotlinx.coroutines.flow.*
import org.ramflux.data.*

fun agentRecord(id: String, name: String = "Agent $id", lifecycle: AgentLifecycle = AgentLifecycle.ACTIVE) = AgentSummary(
    id,
    AgentPersona(name, "orbit", "Research partner"),
    lifecycle,
    "named",
    AgentAccess("owner", "contacts", 0)
)

/** Serves `agent.list` as offset pages of eight over [records]; records every call for assertions. */
class FakeAgentDirectory : AgentDirectoryRepository {
    var records: List<AgentSummary> = listOf(agentRecord("nova", "Nova"), agentRecord("atlas", "Atlas"), agentRecord("mira", "Mira"))
    var listing: (suspend () -> ClientResult<Unit>)? = null
    var changed: List<AgentSummary> = emptyList()
    var waiting: PendingAgentCreation? = null
    var dm: String? = "dm:nova"
    val cursors = mutableListOf<String?>()
    val edits = mutableListOf<Pair<String, AgentPersona>>()
    val deletes = mutableListOf<String>()
    var watches = 0
    override suspend fun agentPage(account: String, cursor: String?): ClientResult<AgentPage> {
        cursors.add(cursor)
        listing?.let { gate -> (gate() as? ClientResult.Failed)?.let { return it } }
        val start = cursor?.toInt() ?: 0
        val end = minOf(start + 8, records.size)
        return ClientResult.Ready(
            AgentPage(
                WindowedPage(
                    records.subList(start, end).toList(),
                    if (end < records.size) "$end" else null,
                    if (start > 0) "${maxOf(start - 8, 0)}" else null
                ),
                "j${records.size}"
            )
        )
    }
    override suspend fun agent(account: String, anchor: String): ClientResult<AgentSummary> = records.find {
        it.anchor == anchor
    }?.let { ClientResult.Ready(it) } ?: ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    override suspend fun changes(account: String, journal: String): ClientResult<AgentChanges> {
        watches++
        return ClientResult.Ready(AgentChanges(changed, journal + "+", false))
    }
    override suspend fun pendingCreation(account: String): ClientResult<PendingAgentCreation?> = ClientResult.Ready(waiting)
    override suspend fun editPersona(
        account: String,
        anchor: String,
        persona: AgentPersona,
        confirmed: Boolean
    ): ClientResult<AgentSummary> {
        if (!confirmed) return ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))
        edits.add(anchor to persona)
        val updated = records.first { it.anchor == anchor }.copy(persona = persona)
        records = records.map { if (it.anchor == anchor) updated else it }
        return ClientResult.Ready(updated)
    }
    override suspend fun delete(account: String, anchor: String, confirmed: Boolean): ClientResult<AgentSummary> {
        if (!confirmed) return ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))
        deletes.add(anchor)
        val retired = records.first { it.anchor == anchor }.copy(lifecycle = AgentLifecycle.RETIRED)
        records = records.filterNot { it.anchor == anchor }
        return ClientResult.Ready(retired)
    }
    override suspend fun conversation(account: String, anchor: String): ClientResult<String?> = ClientResult.Ready(dm)
}
class FakeAgentAccounts : AccountAccessRepository {
    var result: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    override suspend fun list() = result
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeAgentSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
