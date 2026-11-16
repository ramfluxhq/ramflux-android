// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain
// Generated from the pinned SDK method policy.

package org.ramflux.data

import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

interface DevicesRepository {
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
    suspend fun transferAffirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferJoin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferPrepare(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
    suspend fun transferStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply>
    suspend fun transferTail_abandon(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply>
}

class DefaultDevicesRepository(transport: RepositoryTransport) : SdkRepository(transport), DevicesRepository {
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

    override suspend fun transferAffirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.affirm", "device", context, body)

    override suspend fun transferBegin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.begin", "device", context, body)

    override suspend fun transferCancel(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.cancel", "device", context, body)

    override suspend fun transferConfirm(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.confirm", "device", context, body)

    override suspend fun transferJoin(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.join", "device", context, body)

    override suspend fun transferPrepare(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.prepare", "device", context, body)

    override suspend fun transferStatus(context: RepositoryContext, body: JsonObject): CoreResult<SecretReply> =
        executeSecret("device.transfer.status", "device", context, SecretBody.publicJson("device.transfer.status", body))

    override suspend fun transferTail_abandon(context: RepositoryContext, body: SecretBody): CoreResult<SecretReply> =
        executeSecret("device.transfer.tail_abandon", "device", context, body)
}
