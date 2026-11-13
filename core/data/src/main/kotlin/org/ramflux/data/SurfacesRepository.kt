// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface SurfacesRepository {
    suspend fun a2iAcknowledge(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun a2iAppend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun a2iList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun a2iList_pending(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun a2uiAction(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun a2uiRender(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun surfaceAction(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun surfaceGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun surfaceList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun surfaceRequest_resync(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun surfaceResync(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun surfaceSend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultSurfacesRepository(transport: RepositoryTransport) : SdkRepository(transport), SurfacesRepository {
    override suspend fun a2iAcknowledge(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("a2i.acknowledge", "a2i", context, body)

    override suspend fun a2iAppend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("a2i.append", "a2i", context, body)

    override suspend fun a2iList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("a2i.list", "a2i", context, SecretBody.publicJson("a2i.list", body))

    override suspend fun a2iList_pending(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("a2i.list_pending", "a2i", context, SecretBody.publicJson("a2i.list_pending", body))

    override suspend fun a2uiAction(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("a2ui.action", "a2ui", context, body)

    override suspend fun a2uiRender(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("a2ui.render", "a2ui", context, body)

    override suspend fun surfaceAction(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("surface.action", "surface", context, body)

    override suspend fun surfaceGet(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("surface.get", "surface", context, SecretBody.publicJson("surface.get", body))

    override suspend fun surfaceList(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("surface.list", "surface", context, SecretBody.publicJson("surface.list", body))

    override suspend fun surfaceRequest_resync(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("surface.request_resync", "surface", context, body)

    override suspend fun surfaceResync(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("surface.resync", "surface", context, body)

    override suspend fun surfaceSend(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("surface.send", "surface", context, body)
}
