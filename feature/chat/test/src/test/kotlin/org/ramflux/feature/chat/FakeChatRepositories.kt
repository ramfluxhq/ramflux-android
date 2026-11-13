// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
class FakeChatHistory : MessageHistoryRepository {
    var reads = 0
    var sends = 0
    var reading: suspend (String) -> ClientResult<List<MessageSummary>> = { ClientResult.Ready(emptyList()) }
    override suspend fun read(account: String, conversation: String): ClientResult<List<MessageSummary>> {
        reads++
        return reading(conversation)
    }
    var capability: ClientResult<Unit> = ClientResult.Unavailable(
        ClientFeature.CHAT,
        UnavailableReason.UNSUPPORTED_BY_CORE
    )
    override suspend fun sendAvailability(): ClientResult<Unit> = capability
    override suspend fun send(account: String, conversation: String, text: String): ClientResult<Unit> {
        sends++
        return sendAvailability()
    }
}
class FakeChatAccounts : AccountAccessRepository {
    var active = "a"
    var failure: ClientResult<AccountCatalog>? = null
    override suspend fun list(): ClientResult<AccountCatalog> = failure ?: ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary(active, "Work", true, "unlocked")), active)
    )
    override suspend fun select(id: String): ClientResult<Unit> {
        active = id
        return ClientResult.Ready(Unit)
    }
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeChatSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
