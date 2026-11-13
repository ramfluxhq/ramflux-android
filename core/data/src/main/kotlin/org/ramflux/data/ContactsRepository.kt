// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface ContactsRepository {
    suspend fun accept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun add(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun block(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun cardExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun cardImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun decline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun reject(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun rejected(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun remove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun request(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun requestAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun requestBlock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun requestDecline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun requestsPolicyGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun requestsPolicySet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun safety_number(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun unblock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun verificationStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun verify(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultContactsRepository(transport: RepositoryTransport) : SdkRepository(transport), ContactsRepository {
    override suspend fun accept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.accept", "contact", context, body)

    override suspend fun add(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.add", "contact", context, body)

    override suspend fun block(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.block", "contact", context, body)

    override suspend fun cardExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.card.export", "contact", context, body)

    override suspend fun cardImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.card.import", "contact", context, body)

    override suspend fun decline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.decline", "contact", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("contact.list", "contact", context, SecretBody.publicJson("contact.list", body))

    override suspend fun reject(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.reject", "contact", context, body)

    override suspend fun rejected(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("contact.rejected", "contact", context, SecretBody.publicJson("contact.rejected", body))

    override suspend fun remove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.remove", "contact", context, body)

    override suspend fun request(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.request", "contact", context, body)

    override suspend fun requestAccept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.request.accept", "contact", context, body)

    override suspend fun requestBlock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.request.block", "contact", context, body)

    override suspend fun requestDecline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.request.decline", "contact", context, body)

    override suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("contact.requests.list", "contact", context, SecretBody.publicJson("contact.requests.list", body))

    override suspend fun requestsPolicyGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("contact.requests.policy.get", "contact", context, SecretBody.publicJson("contact.requests.policy.get", body))

    override suspend fun requestsPolicySet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.requests.policy.set", "contact", context, body)

    override suspend fun safety_number(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.safety_number", "contact", context, body)

    override suspend fun unblock(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.unblock", "contact", context, body)

    override suspend fun verificationStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("contact.verification.status", "contact", context, SecretBody.publicJson("contact.verification.status", body))

    override suspend fun verify(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("contact.verify", "contact", context, body)
}
