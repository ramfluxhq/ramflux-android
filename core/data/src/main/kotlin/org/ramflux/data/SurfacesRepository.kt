// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the surfaces flows. No retries or optimistic outcomes. */
interface SurfacesRepository {
    suspend fun surfaceAction(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun surfaceGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun surfaceList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun surfaceRequest_resync(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun surfaceResync(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun surfaceSend(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultSurfacesRepository(transport: RepositoryTransport) : SdkRepository(transport), SurfacesRepository {
    override suspend fun surfaceAction(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.action", "surface", context, body)

    override suspend fun surfaceGet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.get", "surface", context, body)

    override suspend fun surfaceList(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.list", "surface", context, body)

    override suspend fun surfaceRequest_resync(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.request_resync", "surface", context, body)

    override suspend fun surfaceResync(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.resync", "surface", context, body)

    override suspend fun surfaceSend(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("surface.send", "surface", context, body)
}
