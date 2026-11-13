// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import kotlinx.coroutines.flow.MutableStateFlow
import org.ramflux.data.*

/** Fakes retain the production contract: read failures are not empty successes; setup needs custody. */
class FakeAccountAccess : AccountAccessRepository {
    var reads = 0
    var selections = 0
    var setups = 0
    var listing: suspend () -> ClientResult<AccountCatalog> = { ClientResult.Ready(AccountCatalog(emptyList(), null)) }
    override suspend fun list(): ClientResult<AccountCatalog> {
        reads++
        return listing()
    }
    override suspend fun select(id: String): ClientResult<Unit> {
        selections++
        return when (val result = list()) {
            is ClientResult.Ready -> if (result.value.accounts.any { it.id == id && it.unlocked }) {
                ClientResult.Ready(
                    Unit
                )
            } else {
                unavailable()
            }
            is ClientResult.Failed -> result
            is ClientResult.Unavailable -> result
        }
    }
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> {
        setups++
        return unavailable()
    }
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
class FakeNodeSetup : NodeSetupRepository {
    var reads = 0
    var discoveries = 0
    var lastAddress: String? = null
    var confirmations = 0
    var lastConfirmed: DiscoveredNode? = null
    var listing: ClientResult<List<SavedNode>> = ClientResult.Ready(emptyList())
    var discoverResult: suspend () -> ClientResult<DiscoveredNode> = { ClientResult.Ready(candidate()) }
    var confirmResult: ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun list(): ClientResult<List<SavedNode>> {
        reads++
        return listing
    }
    override suspend fun discover(address: String): ClientResult<DiscoveredNode> {
        discoveries++
        lastAddress = address
        return discoverResult()
    }
    override suspend fun confirm(candidate: DiscoveredNode): ClientResult<Unit> {
        confirmations++
        lastConfirmed = candidate
        return if (candidate.node.keyChanged) {
            ClientResult.Unavailable(
                ClientFeature.ONBOARDING,
                UnavailableReason.UNSUPPORTED_BY_CORE
            )
        } else {
            confirmResult
        }
    }
    override suspend fun select(id: String): ClientResult<Unit> = when (val result = list()) {
        is ClientResult.Ready -> if (result.value.any { it.id == id && !it.keyChanged && it.enabled }) {
            ClientResult.Ready(Unit)
        } else {
            ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        is ClientResult.Failed -> result
        is ClientResult.Unavailable -> result
    }
    companion object {
        fun candidate(changed: Boolean = false) =
            DiscoveredNode(SavedNode("node", "https://node.example", "public-key", false, changed, true), "a".repeat(64))
    }
}
class FakeOnboardingSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
