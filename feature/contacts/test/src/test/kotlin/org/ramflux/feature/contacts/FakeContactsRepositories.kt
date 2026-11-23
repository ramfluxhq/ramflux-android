// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
class FakeContactDirectory : ContactDirectoryRepository {
    var safetyResult: ClientResult<ContactSafety> = ClientResult.Ready(ContactSafety("1234 5678", "abcdef"))
    override suspend fun safety(account: String, root: String) = safetyResult
    val conversationRoots = mutableListOf<String>()
    var conversationResult: ClientResult<String?> = ClientResult.Ready("dm:peer")
    override suspend fun conversation(account: String, root: String): ClientResult<String?> {
        conversationRoots.add(root)
        return conversationResult
    }
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
    var blockedReading: suspend (String) -> ClientResult<List<ContactSummary>> = { ClientResult.Ready(emptyList()) }
    var pageSize = 100
    val cursors = mutableListOf<String?>()
    val live = MutableSharedFlow<ClientResult<ContactChange>>(extraBufferCapacity = 16)
    var watchers = 0
    override fun changes(account: String): Flow<ClientResult<ContactChange>> = kotlinx.coroutines.flow.flow {
        watchers++
        try {
            live.collect { emit(it) }
        } finally {
            watchers--
        }
    }
    override suspend fun contactPage(
        account: String,
        scope: ContactListScope,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<ContactSummary>> {
        cursors.add(cursor)
        val listed = if (scope == ContactListScope.BLOCKED) {
            reads++
            blockedReading(account)
        } else {
            contacts(account)
        }
        return paged(listed, cursor) { query.isBlank() || it.principal.contains(query, true) || it.name?.contains(query, true) == true }
    }
    override suspend fun requestPage(
        account: String,
        scope: ContactRequestScope,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<ContactRequestSummary>> {
        cursors.add(cursor)
        return paged(requests(account), cursor) {
            val stranger = it.direction == RequestDirection.INBOUND && it.status == ContactRequestStatus.PENDING
            (if (scope == ContactRequestScope.STRANGERS) stranger else it.direction == RequestDirection.OUTBOUND) &&
                (query.isBlank() || it.principal.contains(query, true) || it.name?.contains(query, true) == true)
        }
    }
    private fun <T> paged(listed: ClientResult<List<T>>, cursor: String?, keep: (T) -> Boolean): ClientResult<WindowedPage<T>> =
        when (listed) {
            is ClientResult.Ready -> {
                val rows = listed.value.filter(keep)
                val offset = cursor?.toInt() ?: 0
                val end = (offset + pageSize).coerceAtMost(rows.size)
                ClientResult.Ready(
                    WindowedPage(
                        rows.subList(offset, end),
                        if (end < rows.size) "$end" else null,
                        if (offset > 0) "${(offset - pageSize).coerceAtLeast(0)}" else null
                    )
                )
            }
            is ClientResult.Failed -> listed
            is ClientResult.Unavailable -> listed
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
