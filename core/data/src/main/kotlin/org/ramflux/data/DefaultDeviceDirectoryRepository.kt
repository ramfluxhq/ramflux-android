// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.*

internal class DefaultDeviceDirectoryRepository(transport: RepositoryTransport) : DeviceDirectoryRepository {
    private val source = DefaultDevicesRepository(transport)
    override suspend fun list(account: String): ClientResult<List<DeviceSummary>> {
        if (account.isBlank() || account.length > 4096) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        return when (val result = source.list(RepositoryContext(account), JsonObject(emptyMap()))) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = SettingsMetadataReader.devices(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(decoded.value.map { DeviceSummary(it.id, it.local, it.epoch) })
            }
        }
    }
    /**
     * `device.revoke {"device_id"}` for another device of the account. Success is reported only after
     * the SDK accepted the revocation; the caller rereads the list instead of removing a row itself.
     */
    override suspend fun revoke(account: String, device: String): ClientResult<Unit> {
        if (account.isBlank() || account.length > 4096 || device.isBlank() || device.length > 4096) {
            return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        val body = SecretBody.fields { publicText("device_id", device) }
        return when (val result = source.revoke(RepositoryContext(account, UUID.randomUUID().toString()), body)) {
            is CoreResult.Failure -> ClientResult.Failed(result.appProblem())
            is CoreResult.Success -> {
                result.value.close()
                ClientResult.Ready(Unit)
            }
        }
    }
}
