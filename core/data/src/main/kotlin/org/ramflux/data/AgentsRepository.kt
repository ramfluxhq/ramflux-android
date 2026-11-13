// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the agents flows. No retries or optimistic outcomes. */
interface AgentsRepository {
    suspend fun install(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun revoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun trustAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultAgentsRepository(transport: RepositoryTransport) : SdkRepository(transport), AgentsRepository {
    override suspend fun install(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("bot.install", "bot", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("bot.list", "bot", context, body)

    override suspend fun revoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("bot.revoke", "bot", context, body)

    override suspend fun trustAdd(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("bot.trust.add", "bot", context, body)
}
