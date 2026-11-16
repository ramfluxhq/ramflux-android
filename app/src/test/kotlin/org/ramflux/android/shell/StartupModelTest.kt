// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.AccountCatalog
import org.ramflux.data.AccountSetupAction
import org.ramflux.data.AccountSummary
import org.ramflux.data.ClientFeature
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ClientSnapshot
import org.ramflux.data.DiscoveredNode
import org.ramflux.data.NodeSetupRepository
import org.ramflux.data.SavedNode
import org.ramflux.data.UnavailableReason

@OptIn(ExperimentalCoroutinesApi::class)
class StartupModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    @Test fun successfulUnlockReachesTheShellWithoutASessionChange() = runTest(timeout = 5.seconds) {
        val session = FixedSession(ClientPhase.OPEN)
        val accounts = RevisingAccounts(locked())
        val model = StartupModel(session, accounts, ReadyNodes())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.ui.collect {} }
        runCurrent()
        assertEquals(StartupPlace.UNLOCK, model.ui.value.place)
        assertEquals(ClientPhase.OPEN, session.status.value.phase)
        accounts.catalog = unlocked()
        assertEquals(true, accounts.revisionsFlow.tryEmit(1))
        runCurrent()
        assertEquals(StartupPlace.SHELL, model.ui.value.place)
        assertEquals(ClientPhase.OPEN, session.status.value.phase)
    }
}

private fun locked() = AccountCatalog(listOf(AccountSummary("a", "Ada", false, "locked")), "a")

private fun unlocked() = AccountCatalog(listOf(AccountSummary("a", "Ada", true, "unlocked")), "a")

private class FixedSession(phase: ClientPhase) : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(phase))
    override suspend fun foreground() = Unit
    override suspend fun background() = Unit
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}

private class RevisingAccounts(initial: AccountCatalog) : AccountAccessRepository {
    val revisionsFlow = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    override val revisions: Flow<Int> = revisionsFlow
    var catalog = initial
    override suspend fun list(): ClientResult<AccountCatalog> = ClientResult.Ready(catalog)
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Ready(Unit)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}

private class ReadyNodes : NodeSetupRepository {
    override suspend fun list(): ClientResult<List<SavedNode>> = ClientResult.Ready(
        listOf(SavedNode("n", "https://node.example", "k", true, false, true))
    )
    override suspend fun discover(address: String): ClientResult<DiscoveredNode> = unavailable()
    override suspend fun confirm(candidate: DiscoveredNode, operatorKey: String): ClientResult<Unit> = unavailable()
    override suspend fun select(id: String): ClientResult<Unit> = unavailable()
    private fun unavailable() = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
}
