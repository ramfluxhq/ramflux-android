// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface DevicesRepository {
    suspend fun activate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun authorize(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun migrate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairApprove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairJoin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun pairStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun push_routeSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun refresh(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun syncExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun syncImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultDevicesRepository(transport: RepositoryTransport) : SdkRepository(transport), DevicesRepository {
    override suspend fun activate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.activate", "device", context, body)

    override suspend fun authorize(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.authorize", "device", context, body)

    override suspend fun list(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("device.list", "device", context, SecretBody.publicJson("device.list", body))

    override suspend fun migrate(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.migrate", "device", context, body)

    override suspend fun pairApprove(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.pair.approve", "device", context, body)

    override suspend fun pairBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.pair.begin", "device", context, body)

    override suspend fun pairCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.pair.cancel", "device", context, body)

    override suspend fun pairConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.pair.confirm", "device", context, body)

    override suspend fun pairJoin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.pair.join", "device", context, body)

    override suspend fun pairStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("device.pair.status", "device", context, SecretBody.publicJson("device.pair.status", body))

    override suspend fun push_routeSet(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.push_route.set", "device", context, body)

    override suspend fun refresh(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.refresh", "device", context, body)

    override suspend fun revoke(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.revoke", "device", context, body)

    override suspend fun syncExport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.sync.export", "device", context, body)

    override suspend fun syncImport(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.sync.import", "device", context, body)
}
