// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
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
    /** The native approval/custody interaction is not installed; this never invokes a revoke optimistically. */
    override suspend fun revoke(account: String, device: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.SETTINGS, UnavailableReason.FEATURE_NOT_INSTALLED)
}
