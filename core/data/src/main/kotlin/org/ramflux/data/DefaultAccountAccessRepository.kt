// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import org.ramflux.sdk.*

internal class DefaultAccountAccessRepository(transport: RepositoryTransport) : AccountAccessRepository {
    private val accounts = DefaultAccountsRepository(transport)
    override suspend fun list(): ClientResult<AccountCatalog> = when (val result = accounts.accountList(RepositoryContext(null), JsonObject(emptyMap()))) {
        is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
        is CoreResult.Success -> when (val decoded = AccountListMetadataReader.read(result.value)) {
            is CoreResult.Failure -> ClientResult.Failed(decoded.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(AccountCatalog(decoded.value.accounts.map {
                AccountSummary(it.id, it.label, it.state == "unlocked", it.state)
            }, decoded.value.activeAccountId))
        }
    }
    override suspend fun select(id: String): ClientResult<Unit> {
        // Authoritative state is checked for every selection, not trusted from a previous screen.
        val catalog = when (val current = list()) {
            is ClientResult.Ready -> current.value
            is ClientResult.Failed -> return current
            is ClientResult.Unavailable -> return current
        }
        if (catalog.accounts.none { it.id == id && it.unlocked }) return unavailable()
        return when (val result = accounts.accountSwitch(RepositoryContext(id, UUID.randomUUID().toString()), SecretBody.fields {})) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            is CoreResult.Success -> { result.value.close(); ClientResult.Ready(Unit) }
        }
    }
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = unavailable()
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
