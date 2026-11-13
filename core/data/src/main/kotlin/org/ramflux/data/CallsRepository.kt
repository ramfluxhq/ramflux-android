// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface CallsRepository {
    suspend fun answer(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun hangup(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun invite(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun mediaSession(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun reject(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultCallsRepository(transport: RepositoryTransport) : SdkRepository(transport), CallsRepository {
    override suspend fun answer(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("call.answer", "call", context, body)

    override suspend fun hangup(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("call.hangup", "call", context, body)

    override suspend fun invite(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("call.invite", "call", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("call.list", "call", context, SecretBody.publicJson("call.list", body))

    override suspend fun mediaSession(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("call.media.session", "call", context, body)

    override suspend fun reject(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("call.reject", "call", context, body)
}
