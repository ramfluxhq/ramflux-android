// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Metadata is ordinary display information; credentials and ceremony replies never cross Data. */
/** One account on this device as the SDK's `account.list` reports it, open or locked; [label] is its display name. */
data class AccountSummary(
    val id: String,
    val label: String?,
    val unlocked: Boolean,
    val state: String
)
data class AccountCatalog(val accounts: List<AccountSummary>, val activeId: String?)
enum class AccountSetupAction { CREATE, RECOVER, PAIR, SHOW_PHRASE, CHECK_PHRASE, LOCAL_UNLOCK }
interface AccountAccessRepository {
    /** Increments after a successful switch. Unlock publishes through that switch. */
    val revisions: Flow<Int>
        get() = emptyFlow()

    suspend fun list(): ClientResult<AccountCatalog>

    /**
     * The display name [list] last reported for [id] in this process, or null. Core leaves the name out of an app-locked
     * account's row, so the lock cover and its remove dialog name the account they are bound to from this. Memory only:
     * never persisted or logged, keyed by the account id, dropped when the account leaves the list or is removed.
     */
    fun knownLabel(id: String): String? = null
    suspend fun select(id: String): ClientResult<Unit>
    suspend fun setup(action: AccountSetupAction): ClientResult<Unit>

    /**
     * A locked account stays locked until this call with its six-digit account code. The code array
     * is wiped before return. Refusals carry the SDK's names (see [accountCodeRefusal]) and waits.
     */
    suspend fun unlock(id: String, code: CharArray): ClientResult<Unit> {
        code.fill('\u0000')
        return ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
    }

    /** Explicit lock: closes the account's data; only its account code opens it again on this device. */
    suspend fun lock(id: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)

    /**
     * Whether the SDK reports the account's networking as `live` now (`account.status`). A newly created account
     * starts pending and turns live once its node session is established.
     */
    suspend fun networkingLive(id: String): ClientResult<Boolean> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)

    /**
     * Signs the account out on this phone (`account.sign_out`): the host's account-scoped stores are erased first, then
     * the SDK closes the account like a lock; only its account code opens it again.
     */
    suspend fun signOut(id: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)

    /**
     * Removes the account and all its local data from this phone (`account.remove_local`, confirmed by the account id):
     * the host's account-scoped stores, then the SDK's keys, database and custody record. Nothing is sent to the node.
     */
    suspend fun removeLocal(id: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)

    /**
     * One item per account a screen removed from this device: the display name its confirmation showed, or null when it
     * showed the neutral word. The app draws the notice in its own window above the destination (the picker or
     * onboarding), so it survives the removing screen going away. Memory only: never persisted or logged.
     */
    val removedNotices: Flow<String?>
        get() = emptyFlow()

    /** Posts the removal notice for an account the caller has just removed with [removeLocal]. */
    fun announceRemoved(name: String?) = Unit
}
