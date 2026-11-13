// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the calls flows. No retries or optimistic outcomes. */
interface CallsRepository {
    suspend fun answer(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun hangup(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun invite(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun mediaSession(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun reject(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultCallsRepository(transport: RepositoryTransport) : SdkRepository(transport), CallsRepository {
    override suspend fun answer(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.answer", "call", context, body)

    override suspend fun hangup(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.hangup", "call", context, body)

    override suspend fun invite(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.invite", "call", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.list", "call", context, body)

    override suspend fun mediaSession(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.media.session", "call", context, body)

    override suspend fun reject(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("call.reject", "call", context, body)
}
