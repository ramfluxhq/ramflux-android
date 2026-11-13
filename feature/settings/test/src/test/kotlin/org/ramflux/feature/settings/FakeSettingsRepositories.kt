// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*
class FakeSettingsAccounts : AccountAccessRepository {
    var reads = 0
    var result: ClientResult<AccountCatalog> = ClientResult.Ready(
        AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a")
    )
    override suspend fun list(): ClientResult<AccountCatalog> {
        reads++
        return result
    }
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeDeviceDirectory : DeviceDirectoryRepository {
    var reads = 0
    var revokes = 0
    var listing: suspend () -> ClientResult<List<DeviceSummary>> = { ClientResult.Ready(emptyList()) }
    override suspend fun list(account: String): ClientResult<List<DeviceSummary>> {
        reads++
        return listing()
    }
    override suspend fun revoke(account: String, device: String): ClientResult<Unit> {
        revokes++
        return ClientResult.Unavailable(ClientFeature.SETTINGS)
    }
}
class FakeAccountStatus : AccountStatusRepository {
    var reads = 0
    var result: ClientResult<AccountStatus> = ClientResult.Ready(
        AccountStatus(SecureSessions.READY, SyncPhase.NOT_RECORDED, NodeHealth.NOT_RECORDED, null)
    )
    override suspend fun read(account: String): ClientResult<AccountStatus> {
        reads++
        return result
    }
    override suspend fun backupAvailability(): ClientResult<Unit> = ClientResult.Unavailable(
        ClientFeature.SETTINGS,
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED
    )
}
class FakeSettingsAppearance : AppearanceRepository {
    override val appearance = MutableStateFlow(AppearanceSnapshot(loading = false))
    var writes = 0
    var result: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun setAppearance(value: Appearance): ClientResult<Unit> {
        writes++
        if (result is ClientResult.Ready) appearance.value = AppearanceSnapshot(value, loading = false)
        return result
    }
    override suspend fun setNamedTheme(id: String): ClientResult<Unit> {
        writes++
        if (result is ClientResult.Ready) appearance.value = AppearanceSnapshot(loading = false, themeId = id)
        return result
    }
}
class FakeSettingsSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}

class FakeSettingsLanguage : LanguageRepository {
    override val language = MutableStateFlow(LanguageSnapshot(loading = false))
    var result: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun setLanguage(value: AppLanguage): ClientResult<Unit> {
        if (result is ClientResult.Ready) language.value = LanguageSnapshot(value, loading = false)
        return result
    }
}
