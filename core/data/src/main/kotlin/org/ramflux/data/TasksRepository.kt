// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface TasksRepository {
    suspend fun accept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun cancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun decisionProve(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun decline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun get(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun propose(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun resolve(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun start(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun update(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultTasksRepository(transport: RepositoryTransport) : SdkRepository(transport), TasksRepository {
    override suspend fun accept(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.accept", "task", context, body)

    override suspend fun cancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.cancel", "task", context, body)

    override suspend fun decisionProve(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.decision.prove", "task", context, body)

    override suspend fun decline(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.decline", "task", context, body)

    override suspend fun get(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("task.get", "task", context, SecretBody.publicJson("task.get", body))

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("task.list", "task", context, SecretBody.publicJson("task.list", body))

    override suspend fun propose(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.propose", "task", context, body)

    override suspend fun resolve(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.resolve", "task", context, body)

    override suspend fun start(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.start", "task", context, body)

    override suspend fun update(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("task.update", "task", context, body)
}
