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
    var unlocks = 0
    var lastUnlockId: String? = null
    var unlockResult: ClientResult<Unit> = ClientResult.Ready(Unit)
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
    override suspend fun unlock(id: String, passphrase: CharArray): ClientResult<Unit> {
        unlocks++
        lastUnlockId = id
        passphrase.fill('\u0000')
        return unlockResult
    }
    val forgotten = mutableListOf<String>()
    override suspend fun forget(id: String): ClientResult<Unit> {
        forgotten += id
        return ClientResult.Ready(Unit)
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
    override suspend fun confirm(candidate: DiscoveredNode, operatorKey: String): ClientResult<Unit> {
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
class FakeAccountCeremony : AccountCeremonyRepository {
    var begins = 0
    var lastName: String? = null
    var lastNode: String? = null
    var heldWords: Array<CharArray> = emptyArray()
    var beforePhrase: (() -> Unit)? = null
    var beginResult: ClientResult<CeremonyPending>? = null
    var confirmations = 0
    var confirmResult: suspend (Array<CharArray>, CharArray) -> ClientResult<String> = { _, _ ->
        ClientResult.Ready("account-created")
    }
    override suspend fun beginCreate(displayName: String, nodeId: String?): ClientResult<CeremonyPending> {
        begins++
        lastName = displayName
        lastNode = nodeId
        return beginResult ?: ClientResult.Ready(CeremonyPending("ceremony-1", 60_000))
    }
    override suspend fun showPhrase(): ClientResult<RecoveryPhrase> {
        beforePhrase?.invoke()
        heldWords = Array(24) { "alpha$it".toCharArray() }
        return ClientResult.Ready(RecoveryPhrase(heldWords))
    }
    override suspend fun challenge(): ClientResult<IntArray> = ClientResult.Ready(intArrayOf(2, 15, 24))
    override suspend fun confirm(words: Array<CharArray>, passphrase: CharArray): ClientResult<String> {
        confirmations++
        return confirmResult(words, passphrase)
    }
    override suspend fun cancel(): ClientResult<Unit> = ClientResult.Ready(Unit)
    var recoverBegins = 0
    var recoverCancels = 0
    var heldRecovery: Pair<CharArray, CharArray>? = null
    var lastAnchor: String? = null
    var recoverResult: ClientResult<String> = ClientResult.Ready("a")
    override suspend fun beginRecover(accountId: String): ClientResult<RecoveryPending> {
        recoverBegins++
        return ClientResult.Ready(RecoveryPending(accountId, "anchor-abcd-efgh"))
    }
    override suspend fun confirmRecover(phrase: CharArray, code: CharArray, anchor: String): ClientResult<String> {
        heldRecovery = phrase to code
        lastAnchor = anchor
        phrase.fill('\u0000')
        code.fill('\u0000')
        return recoverResult
    }
    override suspend fun cancelRecover(): ClientResult<Unit> {
        recoverCancels++
        return ClientResult.Ready(Unit)
    }
}
class FakeOnboardingSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    fun advanceGeneration() {
        status.value = status.value.copy(generation = status.value.generation + 1)
    }
    fun retire() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun background() {
        retire()
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
