// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** Metadata is ordinary display information; credentials and ceremony replies never cross Data. */
data class AccountSummary(val id: String, val label: String?, val unlocked: Boolean, val state: String)
data class AccountCatalog(val accounts: List<AccountSummary>, val activeId: String?)
enum class AccountSetupAction { CREATE, RECOVER, PAIR, SHOW_PHRASE, CHECK_PHRASE, LOCAL_UNLOCK }
interface AccountAccessRepository {
    suspend fun list(): ClientResult<AccountCatalog>
    suspend fun select(id: String): ClientResult<Unit>
    suspend fun setup(action: AccountSetupAction): ClientResult<Unit>
}
