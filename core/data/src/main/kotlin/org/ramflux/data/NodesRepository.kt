// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the nodes flows. No retries or optimistic outcomes. */
interface NodesRepository {
    suspend fun add(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun confirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun probe(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun remove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun set_default(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun set_enabled(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultNodesRepository(transport: RepositoryTransport) : SdkRepository(transport), NodesRepository {
    override suspend fun add(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.add", "node", context, body)

    override suspend fun confirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.confirm", "node", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.list", "node", context, body)

    override suspend fun probe(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.probe", "node", context, body)

    override suspend fun remove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.remove", "node", context, body)

    override suspend fun set_default(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.set_default", "node", context, body)

    override suspend fun set_enabled(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("node.set_enabled", "node", context, body)
}
