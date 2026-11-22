// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Metadata is ordinary display information; credentials and ceremony replies never cross Data. */
data class AccountSummary(val id: String, val label: String?, val unlocked: Boolean, val state: String)
data class AccountCatalog(val accounts: List<AccountSummary>, val activeId: String?)
enum class AccountSetupAction { CREATE, RECOVER, PAIR, SHOW_PHRASE, CHECK_PHRASE, LOCAL_UNLOCK }
interface AccountAccessRepository {
    /** Increments after a successful switch. Unlock publishes through that switch. */
    val revisions: Flow<Int>
        get() = emptyFlow()

    suspend fun list(): ClientResult<AccountCatalog>
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
}
