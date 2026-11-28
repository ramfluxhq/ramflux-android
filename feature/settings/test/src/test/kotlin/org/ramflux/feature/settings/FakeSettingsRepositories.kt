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
    val locked = mutableListOf<String>()
    override suspend fun lock(id: String): ClientResult<Unit> {
        locked += id
        return ClientResult.Ready(Unit)
    }
    val removed = mutableListOf<String>()
    override suspend fun removeLocal(id: String): ClientResult<Unit> {
        removed += id
        return ClientResult.Ready(Unit)
    }
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
    var rechecks = 0
    override suspend fun recheckAppLock() {
        rechecks++
    }
}

/**
 * Languages of the settings test fixtures: follow-system, the two locales this module has resources for,
 * and a language without resources, which the picker must offer just the same.
 */
val TEST_LANGUAGES = listOf(AppLanguage.SYSTEM, AppLanguage("en"), AppLanguage("zh-CN"), AppLanguage("de"))

class FakeSettingsLanguage : LanguageRepository {
    override val language = MutableStateFlow(LanguageSnapshot(loading = false))
    override val languages = TEST_LANGUAGES
    var result: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun setLanguage(value: AppLanguage): ClientResult<Unit> {
        if (result is ClientResult.Ready) language.value = LanguageSnapshot(value, loading = false)
        return result
    }
}
class FakeAccountSecurity : AccountSecurityRepository {
    val calls = mutableListOf<String>()
    var begin: ClientResult<PairingInvitation>? = null
    val statuses = ArrayDeque<ClientResult<PairingState>>()
    var step: ClientResult<PairingState>? = null
    var policy: ClientResult<ContactRequestPolicy> = ClientResult.Ready(ContactRequestPolicy.MANUAL)
    var code: ClientResult<Unit> = ClientResult.Ready(Unit)
    val codes = mutableListOf<Pair<String, String>>()
    var appLock: ClientResult<AppLockStatus> = ClientResult.Ready(AppLockStatus(true, 0L, AppLockState.UNLOCKED))
    var lockApp: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun appLockState(account: String) = appLock
    override suspend fun lockApp(account: String): ClientResult<Unit> {
        calls += "lock-app:$account"
        return lockApp
    }
    override suspend fun beginPairing(account: String, displayName: String, leaseSeconds: Long): ClientResult<PairingInvitation> {
        calls += "begin"
        return begin ?: ClientResult.Unavailable(ClientFeature.SETTINGS)
    }
    override suspend fun pairingStatus(account: String, operationId: String, generation: ULong): ClientResult<PairingState> {
        calls += "status"
        return statuses.removeFirstOrNull() ?: kotlinx.coroutines.awaitCancellation()
    }
    override suspend fun confirmPairing(account: String, state: PairingState, match: Boolean): ClientResult<PairingState> {
        calls += "confirm:$match"
        return step ?: ClientResult.Unavailable(ClientFeature.SETTINGS)
    }
    override suspend fun approvePairing(account: String, state: PairingState, approved: Boolean): ClientResult<PairingState> {
        calls += "approve:$approved"
        return step ?: ClientResult.Unavailable(ClientFeature.SETTINGS)
    }
    override suspend fun cancelPairing(account: String, operationId: String, generation: ULong): ClientResult<PairingState> {
        calls += "cancel:$operationId"
        return step ?: ClientResult.Unavailable(ClientFeature.SETTINGS)
    }
    override suspend fun requestPolicy(account: String) = policy.also { calls += "policy" }
    override suspend fun setRequestPolicy(account: String, policy: ContactRequestPolicy): ClientResult<ContactRequestPolicy> {
        calls += "policy:${policy.wire}"
        this.policy = ClientResult.Ready(policy)
        return this.policy
    }
    override suspend fun changeAccountCode(account: String, current: CharArray, next: CharArray): ClientResult<Unit> {
        codes += String(current) to String(next)
        current.fill('\u0000')
        next.fill('\u0000')
        return code
    }
}
