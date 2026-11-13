// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
class FakeContactDirectory : ContactDirectoryRepository {
    var safetyResult: ClientResult<ContactSafety> = ClientResult.Ready(ContactSafety("1234 5678", "abcdef"))
    override suspend fun safety(account: String, root: String) = safetyResult
    val submitted = mutableListOf<ContactCommand>()
    var submitting: suspend (ContactCommand) -> ClientResult<Unit> = { ClientResult.Ready(Unit) }
    override suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> {
        if (!confirmed) return ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))
        submitted.add(command)
        return submitting(command)
    }
    override fun attempts(account: String): List<ContactAttempt> = emptyList()
    var reads = 0
    val operations = mutableListOf<ContactOperation>()
    var reading: suspend (String) -> ClientResult<List<ContactSummary>> = { ClientResult.Ready(emptyList()) }
    var requestReading: suspend (String) -> ClientResult<List<ContactRequestSummary>> = { ClientResult.Ready(emptyList()) }
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> {
        reads++
        return reading(account)
    }
    override suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>> {
        reads++
        return requestReading(account)
    }
    var capability: ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.FEATURE_NOT_INSTALLED)
    override suspend fun availability(operation: ContactOperation): ClientResult<Unit> {
        operations.add(operation)
        return capability
    }
}
class FakeContactsAccounts : AccountAccessRepository {
    var result: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    override suspend fun list() = result
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeContactsSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
fun inbound(id: String = "request") = ContactRequestSummary(
    id,
    "peer",
    "Name",
    RequestDirection.INBOUND,
    ContactRequestStatus.PENDING,
    "Peer note",
    ULong.MAX_VALUE,
    1700000000
)
