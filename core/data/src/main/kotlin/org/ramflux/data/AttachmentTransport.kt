// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

/**
 * The pinned SDK admits the thirteen attachment methods (see the generated method policy), but the
 * repository generator has no group for the `attachment` prefix. These forwarders follow the generated
 * shape exactly (method name, capability, secret classification) until the generator gains an
 * `Attachments` group, at which point this file is replaced by the generated one.
 */
interface AttachmentTransport {
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
}

class DefaultAttachmentTransport(transport: RepositoryTransport) : SdkRepository(transport), AttachmentTransport {
    override suspend fun enable(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.enable", "account", context, body)
    override suspend fun importBegin(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.import.begin", "account", context, body)
    override suspend fun importCancel(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.import.cancel", "account", context, body)
    override suspend fun importFinish(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.import.finish", "account", context, body)
    override suspend fun importRead(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.import.read", "account", context, body)
    override suspend fun importStatus(context: RepositoryContext, body: JsonObject) =
        executeSecret("attachment.import.status", "account", context, SecretBody.publicJson("attachment.import.status", body))
    override suspend fun prepareBegin(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.prepare.begin", "account", context, body)
    override suspend fun prepareCancel(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.prepare.cancel", "account", context, body)
    override suspend fun prepareChunk(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.prepare.chunk", "account", context, body)
    override suspend fun prepareFinish(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.prepare.finish", "account", context, body)
    override suspend fun prepareRenew(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.prepare.renew", "account", context, body)
    override suspend fun prepareStatus(context: RepositoryContext, body: JsonObject) =
        executeSecret("attachment.prepare.status", "account", context, SecretBody.publicJson("attachment.prepare.status", body))
    override suspend fun revoke(context: RepositoryContext, body: SecretBody) =
        executeSecret("attachment.revoke", "account", context, body)
}
