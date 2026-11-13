// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.*

internal class DefaultAccountStatusRepository(transport: RepositoryTransport) : AccountStatusRepository {
    private val source = DefaultSettingsRepository(transport)
    override suspend fun read(account: String): ClientResult<AccountStatus> {
        if (account.isBlank() || account.length > 4096) return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        return when (val result = source.statusGet(RepositoryContext(account), JsonObject(emptyMap()))) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> when (val decoded = SettingsMetadataReader.sync(result.value)) {
                is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
                is CoreResult.Success -> ClientResult.Ready(AccountStatus(
                    when (decoded.value.secureSessions) { "ready" -> SecureSessions.READY; "reestablishing" -> SecureSessions.REESTABLISHING; else -> SecureSessions.UNKNOWN },
                    when (decoded.value.sync) { null -> SyncPhase.NOT_RECORDED; "running" -> SyncPhase.RUNNING; "idle" -> SyncPhase.IDLE; "error" -> SyncPhase.ERROR; else -> SyncPhase.UNKNOWN },
                    when (decoded.value.node) { null -> NodeHealth.NOT_RECORDED; "connected" -> NodeHealth.CONNECTED; "unavailable" -> NodeHealth.UNAVAILABLE; else -> NodeHealth.UNKNOWN },
                    decoded.value.lastRun))
            }
        }
    }
    override suspend fun backupAvailability(): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.SETTINGS, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
