// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.*

/** [decoding] runs snapshot decoding and projection, never the caller's (main) thread. */
internal class DefaultContactDirectoryRepository(
    private val transport: RepositoryTransport,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) : ContactDirectoryRepository {
    private val source = DefaultContactsRepository(transport)

    /** The last compare-and-set stamp per (account, root), read by [safety] and spent by VERIFY. */
    private val stamps = object : LinkedHashMap<Pair<String, String>, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, String>) = size > 32
    }
    private val actions = ContactCommandAdapter(transport) { account, root -> synchronized(stamps) { stamps[account to root] } }
    private val contactPages = SnapshotPager<ContactSummary>(PAGE)
    private val requestPages = SnapshotPager<ContactRequestSummary>(PAGE)
    override suspend fun submit(command: ContactCommand, confirmed: Boolean) = actions.submit(command, confirmed)
    override fun attempts(account: String) = actions.attempts(account)
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> = links(account, ContactListScope.ACCEPTED)

    override suspend fun contactPage(account: String, scope: ContactListScope, query: String, cursor: String?): ClientResult<WindowedPage<ContactSummary>> {
        if (query.length > 256) return invalid()
        return contactPages.page("$account\u0000$scope\u0000$query", cursor) {
            when (val listed = links(account, scope)) {
                is ClientResult.Ready -> ClientResult.Ready(withContext(decoding) { listed.value.filter { it.matches(query) } })
                else -> listed
            }
        }
    }

    override suspend fun requestPage(account: String, scope: ContactRequestScope, query: String, cursor: String?): ClientResult<WindowedPage<ContactRequestSummary>> {
        if (query.length > 256) return invalid()
        return requestPages.page("$account\u0000$scope\u0000$query", cursor) {
            when (val listed = requests(account)) {
                is ClientResult.Ready -> ClientResult.Ready(
                    withContext(decoding) {
                        listed.value.filter {
                            when (scope) {
                                ContactRequestScope.STRANGERS -> it.direction == RequestDirection.INBOUND && it.status == ContactRequestStatus.PENDING
                                ContactRequestScope.SENT -> it.direction == RequestDirection.OUTBOUND
                            } && it.matches(query)
                        }
                    }
                )
                else -> listed
            }
        }
    }

    override fun changes(account: String): Flow<ClientResult<ContactChange>> = flow {
        if (account.isBlank() || account.length > 4096) {
            emit(invalid())
            return@flow
        }
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", buildJsonArray { TOPICS.forEach { add(it) } }) })
        transport.updates(filter).collect { update ->
            when (update) {
                // Only the topic is read: every change reloads the visible window of its kind.
                is CoreUpdate.Events -> emit(ClientResult.Ready(change(update.body)))
                is CoreUpdate.Failure -> emit(ClientResult.Failed(update.error.appProblem()))
                is CoreUpdate.Terminal -> emit(
                    if (update.kind == "resync_required") ClientResult.Ready(ContactChange.RELOAD)
                    else ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
                )
            }
        }
    }

    private fun change(body: SecretReply): ContactChange = when (EventTopicReader.topic(body)) {
        "contact.changed" -> ContactChange.CONTACTS
        "safety.changed" -> ContactChange.SAFETY
        in REQUEST_TOPICS -> ContactChange.REQUESTS
        else -> ContactChange.RELOAD
    }

    private suspend fun links(account: String, scope: ContactListScope): ClientResult<List<ContactSummary>> {
        if (account.isBlank() || account.length > 4096) return invalid()
        return when (val reply = source.list(RepositoryContext(account), buildJsonObject {})) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> when (val result = ContactDirectoryMetadataReader.contacts(reply.value, decoding)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(withContext(decoding) {
                    result.value.filter {
                        when (scope) {
                            ContactListScope.ACCEPTED -> it.relation == "accepted" && !it.blocked
                            ContactListScope.BLOCKED -> it.blocked
                        }
                    }.map { ContactSummary(it.id, it.principal, it.name, it.verified, it.root) }
                })
            }
        }
    }
    override suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>> {
        if (account.isBlank() || account.length > 4096) return invalid()
        return when (val reply = source.requestsList(RepositoryContext(account), buildJsonObject {})) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> when (val result = ContactDirectoryMetadataReader.requests(reply.value, decoding)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(withContext(decoding) { result.value.map {
                    ContactRequestSummary(it.id, it.principal, it.name,
                        if (it.direction == "inbound") RequestDirection.INBOUND else RequestDirection.OUTBOUND,
                        when (it.state) {
                            "pending" -> ContactRequestStatus.PENDING
                            "accepted" -> ContactRequestStatus.ACCEPTED
                            "declined" -> ContactRequestStatus.DECLINED
                            "blocked" -> ContactRequestStatus.BLOCKED
                            "expired" -> ContactRequestStatus.EXPIRED
                            else -> ContactRequestStatus.UNKNOWN
                        }, it.note, it.count, it.expiresAt, it.root)
                } })
            }
        }
    }
    override suspend fun safety(account: String, root: String): ClientResult<ContactSafety> {
        if (account.isBlank() || root.isBlank() || account.length > 4096 || root.length > 4096) return invalid()
        return when (val reply = source.safety_number(RepositoryContext(account), SecretBody.fields { publicText("contact_identity_commitment", root) })) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> when(val result = ContactSafetyMetadataReader.read(reply.value)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> if (result.value.root == root) {
                    synchronized(stamps) {
                        val expected = result.value.expected
                        if (expected == null) stamps.remove(account to root) else stamps[account to root] = expected
                    }
                    ClientResult.Ready(
                        ContactSafety(
                            result.value.number, result.value.fingerprint,
                            when (result.value.state) {
                                "unverified" -> SafetyState.UNVERIFIED
                                "verified" -> SafetyState.VERIFIED
                                "changed" -> SafetyState.CHANGED
                                "blocked_by_policy" -> SafetyState.BLOCKED_BY_POLICY
                                else -> SafetyState.UNKNOWN
                            },
                            result.value.expected != null
                        )
                    )
                } else {
                    invalid()
                }
            }
        }
    }
    override suspend fun availability(operation: ContactOperation): ClientResult<Unit> =
        if (operation in ContactCommandAdapter.SUPPORTED || operation == ContactOperation.BATCH_DECLINE) ClientResult.Ready(Unit)
        else ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))

    companion object {
        const val PAGE = 100

        /** Journal topics of the pinned SDK (`bus/journal/project.rs`). */
        private val REQUEST_TOPICS = setOf("contact.requested", "contact.accepted", "contact.declined", "contact.blocked", "contact.request.expired")
        val TOPICS = listOf("contact.changed", "safety.changed") + REQUEST_TOPICS
    }
}

private fun ContactSummary.matches(query: String) =
    query.isBlank() || name?.contains(query, ignoreCase = true) == true || principal.contains(query, ignoreCase = true)

private fun ContactRequestSummary.matches(query: String) =
    query.isBlank() || name?.contains(query, ignoreCase = true) == true || principal.contains(query, ignoreCase = true)
