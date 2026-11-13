// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface AgentsRepository {
    suspend fun install(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun trustAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultAgentsRepository(transport: RepositoryTransport) : SdkRepository(transport), AgentsRepository {
    override suspend fun install(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.install", "bot", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("bot.list", "bot", context, SecretBody.publicJson("bot.list", body))

    override suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.revoke", "bot", context, body)

    override suspend fun trustAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.trust.add", "bot", context, body)
}
