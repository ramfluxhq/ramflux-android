// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.ramflux.data.*
class FakeConversationAccounts : AccountAccessRepository {
    var catalog: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    var reads = 0
    var listing: suspend () -> ClientResult<AccountCatalog> = { catalog }
    override suspend fun list(): ClientResult<AccountCatalog> {
        reads++
        return listing()
    }
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
class FakeConversationDirectory : ConversationDirectoryRepository {
    val requests = mutableListOf<Pair<String, String?>>()
    var response: suspend (String?) -> ClientResult<ConversationPage> = { ClientResult.Ready(ConversationPage(emptyList(), null)) }
    var canOpen: ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CHAT)
    override suspend fun page(account: String, cursor: String?): ClientResult<ConversationPage> {
        requests.add(account to cursor)
        return response(cursor)
    }
    var search: ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONVERSATIONS, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun searchAvailability() = search
    override suspend fun openAvailability(account: String, conversation: String) = canOpen
    var pending: ClientResult<Int> = ClientResult.Ready(0)
    var pendingReads = 0
    override suspend fun pendingRequests(account: String): ClientResult<Int> {
        pendingReads++
        return pending
    }
    val live = MutableSharedFlow<ClientResult<ConversationChange>>()
    var watchers = 0
    override fun changes(account: String): Flow<ClientResult<ConversationChange>> = live
        .onStart { watchers++ }
        .onCompletion { watchers-- }
}
class FakeConversationSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
