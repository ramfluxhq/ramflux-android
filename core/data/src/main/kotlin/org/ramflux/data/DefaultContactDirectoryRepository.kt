// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.serialization.json.buildJsonObject
import org.ramflux.sdk.*

internal class DefaultContactDirectoryRepository(transport: RepositoryTransport) : ContactDirectoryRepository {
    private val source = DefaultContactsRepository(transport)
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> {
        if (account.isBlank() || account.length > 4096) return invalid()
        return when (val reply = source.list(RepositoryContext(account), buildJsonObject {})) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> when (val result = ContactDirectoryMetadataReader.contacts(reply.value)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(result.value.filter { it.relation == "accepted" && !it.blocked }.map {
                    ContactSummary(it.id, it.principal, it.name, it.verified)
                })
            }
        }
    }
    override suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>> {
        if (account.isBlank() || account.length > 4096) return invalid()
        return when (val reply = source.requestsList(RepositoryContext(account), buildJsonObject {})) {
            is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
            is CoreResult.Success -> when (val result = ContactDirectoryMetadataReader.requests(reply.value)) {
                is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(result.value.map {
                    ContactRequestSummary(it.id, it.principal, it.name,
                        if (it.direction == "inbound") RequestDirection.INBOUND else RequestDirection.OUTBOUND,
                        when (it.state) {
                            "pending" -> ContactRequestStatus.PENDING
                            "accepted" -> ContactRequestStatus.ACCEPTED
                            "declined" -> ContactRequestStatus.DECLINED
                            "blocked" -> ContactRequestStatus.BLOCKED
                            "expired" -> ContactRequestStatus.EXPIRED
                            else -> ContactRequestStatus.UNKNOWN
                        }, it.note, it.count, it.expiresAt)
                })
            }
        }
    }
    override suspend fun availability(operation: ContactOperation): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONTACTS,
        if (operation == ContactOperation.REPORT) UnavailableReason.UNSUPPORTED_BY_CORE else UnavailableReason.FEATURE_NOT_INSTALLED)
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
}
