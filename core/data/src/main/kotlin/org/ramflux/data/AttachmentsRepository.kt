// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface AttachmentsRepository {
    suspend fun enable(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun importBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun importCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun importFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun importRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun importStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun prepareBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun prepareCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun prepareChunk(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun prepareFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun prepareRenew(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun prepareStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun transferPause(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun transferResume(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultAttachmentsRepository(transport: RepositoryTransport) : SdkRepository(transport), AttachmentsRepository {
    override suspend fun enable(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.enable", "account", context, body)

    override suspend fun importBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.import.begin", "account", context, body)

    override suspend fun importCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.import.cancel", "account", context, body)

    override suspend fun importFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.import.finish", "account", context, body)

    override suspend fun importRead(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.import.read", "account", context, body)

    override suspend fun importStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("attachment.import.status", "account", context, SecretBody.publicJson("attachment.import.status", body))

    override suspend fun prepareBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.begin", "account", context, body)

    override suspend fun prepareCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.cancel", "account", context, body)

    override suspend fun prepareChunk(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.chunk", "account", context, body)

    override suspend fun prepareFinish(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.finish", "account", context, body)

    override suspend fun prepareRenew(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.renew", "account", context, body)

    override suspend fun prepareStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("attachment.prepare.status", "account", context, SecretBody.publicJson("attachment.prepare.status", body))

    override suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("attachment.revoke", "account", context, body)

    override suspend fun transferList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("attachment.transfer.list", "account", context, body)

    override suspend fun transferPause(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("attachment.transfer.pause", "account", context, body)

    override suspend fun transferResume(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("attachment.transfer.resume", "account", context, body)
}
