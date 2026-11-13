// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the tasks flows. No retries or optimistic outcomes. */
interface TasksRepository {
    suspend fun accept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun cancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun decisionProve(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun decline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun get(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun propose(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun start(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun update(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultTasksRepository(transport: RepositoryTransport) : SdkRepository(transport), TasksRepository {
    override suspend fun accept(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.accept", "task", context, body)

    override suspend fun cancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.cancel", "task", context, body)

    override suspend fun decisionProve(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.decision.prove", "task", context, body)

    override suspend fun decline(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.decline", "task", context, body)

    override suspend fun get(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.get", "task", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.list", "task", context, body)

    override suspend fun propose(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.propose", "task", context, body)

    override suspend fun resolve(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.resolve", "task", context, body)

    override suspend fun start(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.start", "task", context, body)

    override suspend fun update(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("task.update", "task", context, body)
}
