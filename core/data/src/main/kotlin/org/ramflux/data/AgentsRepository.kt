// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface AgentsRepository {
    suspend fun agentAccessSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentCreate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentCreationDecide(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentOperationGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentPersonaEdit(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun agentWatch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun botInstall(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun botList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun botRevoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun botTrustAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultAgentsRepository(transport: RepositoryTransport) : SdkRepository(transport), AgentsRepository {
    override suspend fun agentAccessSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.access.set", "agent", context, body)

    override suspend fun agentCreate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.create", "agent", context, body)

    override suspend fun agentCreationDecide(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.creation.decide", "agent", context, body)

    override suspend fun agentGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.get", "agent", context, body)

    override suspend fun agentList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.list", "agent", context, body)

    override suspend fun agentOperationGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.operation.get", "agent", context, body)

    override suspend fun agentPersonaEdit(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.persona.edit", "agent", context, body)

    override suspend fun agentDelete(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.delete", "agent", context, body)

    override suspend fun agentWatch(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("agent.watch", "agent", context, body)

    override suspend fun botInstall(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.install", "bot", context, body)

    override suspend fun botList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("bot.list", "bot", context, SecretBody.publicJson("bot.list", body))

    override suspend fun botRevoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.revoke", "bot", context, body)

    override suspend fun botTrustAdd(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("bot.trust.add", "bot", context, body)
}
