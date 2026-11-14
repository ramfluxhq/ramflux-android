// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
class FakeAgentConfiguration : AgentDirectoryRepository {
    val modes = mutableListOf<AgentCapability>()
    val creates = mutableListOf<AgentDraft>()
    var checking: suspend (AgentCapability) -> ClientResult<Unit> = { ClientResult.Unavailable(ClientFeature.AGENTS) }
    override suspend fun availability(account: String, capability: AgentCapability): ClientResult<Unit> {
        modes.add(capability)
        return checking(capability)
    }
    override suspend fun create(account: String, draft: AgentDraft): ClientResult<String> {
        creates.add(draft)
        return ClientResult.Unavailable(ClientFeature.AGENTS)
    }
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
