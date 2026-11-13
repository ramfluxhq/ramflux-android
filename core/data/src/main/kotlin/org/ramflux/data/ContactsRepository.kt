// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the contacts flows. No retries or optimistic outcomes. */
interface ContactsRepository {
    suspend fun accept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun add(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun block(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun cardExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun cardImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun decline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun reject(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun rejected(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun remove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun request(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestBlock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestDecline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestsPolicyGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun requestsPolicySet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun safety_number(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun unblock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun verificationStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun verify(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultContactsRepository(transport: RepositoryTransport) : SdkRepository(transport), ContactsRepository {
    override suspend fun accept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.accept", "contact", context, body)

    override suspend fun add(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.add", "contact", context, body)

    override suspend fun block(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.block", "contact", context, body)

    override suspend fun cardExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.card.export", "contact", context, body)

    override suspend fun cardImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.card.import", "contact", context, body)

    override suspend fun decline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.decline", "contact", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.list", "contact", context, body)

    override suspend fun reject(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.reject", "contact", context, body)

    override suspend fun rejected(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.rejected", "contact", context, body)

    override suspend fun remove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.remove", "contact", context, body)

    override suspend fun request(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.request", "contact", context, body)

    override suspend fun requestAccept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.request.accept", "contact", context, body)

    override suspend fun requestBlock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.request.block", "contact", context, body)

    override suspend fun requestDecline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.request.decline", "contact", context, body)

    override suspend fun requestsList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.requests.list", "contact", context, body)

    override suspend fun requestsPolicyGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.requests.policy.get", "contact", context, body)

    override suspend fun requestsPolicySet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.requests.policy.set", "contact", context, body)

    override suspend fun safety_number(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.safety_number", "contact", context, body)

    override suspend fun unblock(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.unblock", "contact", context, body)

    override suspend fun verificationStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.verification.status", "contact", context, body)

    override suspend fun verify(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("contact.verify", "contact", context, body)
}
