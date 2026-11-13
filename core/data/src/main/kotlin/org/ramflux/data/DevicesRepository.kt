// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult

/** Supervisor operations for the devices flows. No retries or optimistic outcomes. */
interface DevicesRepository {
    suspend fun activate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun authorize(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun migrate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairApprove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairJoin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun pairStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun push_routeSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun refresh(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun revoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun syncExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
    suspend fun syncImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject>
}

class DefaultDevicesRepository(transport: RepositoryTransport) : SdkRepository(transport), DevicesRepository {
    override suspend fun activate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.activate", "device", context, body)

    override suspend fun authorize(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.authorize", "device", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.list", "device", context, body)

    override suspend fun migrate(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.migrate", "device", context, body)

    override suspend fun pairApprove(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.approve", "device", context, body)

    override suspend fun pairBegin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.begin", "device", context, body)

    override suspend fun pairCancel(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.cancel", "device", context, body)

    override suspend fun pairConfirm(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.confirm", "device", context, body)

    override suspend fun pairJoin(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.join", "device", context, body)

    override suspend fun pairStatus(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.pair.status", "device", context, body)

    override suspend fun push_routeSet(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.push_route.set", "device", context, body)

    override suspend fun refresh(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.refresh", "device", context, body)

    override suspend fun revoke(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.revoke", "device", context, body)

    override suspend fun syncExport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.sync.export", "device", context, body)

    override suspend fun syncImport(context: RepositoryContext, body: JsonObject): CoreResult<JsonObject> =
        execute("device.sync.import", "device", context, body)
}
