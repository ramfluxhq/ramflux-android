// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ramflux.data.*

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: OnboardingViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
    private fun failure(outcome: ClientOutcome = ClientOutcome.NONE) =
        ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, outcome))

    @Test fun failedReadsNeverClaimLoadedEmptyCollections() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply { listing = { failure() } }
        val nodes = FakeNodeSetup().apply { listing = failure() }
        val vm = OnboardingViewModel(accounts, nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.accountsLoaded)
        assertFalse(vm.state.value.nodesLoaded)
        assertNotNull(vm.state.value.notice)
        assertFalse(vm.state.value.busy)
    }

    @Test fun successfulEmptyReadsAreExplicitAndDuplicateRefreshIsDeduplicated() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeAccountAccess().apply {
            listing = {
                barrier.await()
                ClientResult.Ready(AccountCatalog(emptyList(), null))
            }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Refresh)
        vm.onAction(OnboardingAction.Refresh)
        runCurrent()
        assertEquals(1, accounts.reads)
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountsLoaded)
        assertTrue(vm.state.value.nodesLoaded)
        assertNull(vm.state.value.notice)
    }

    @Test fun confirmationUsesReviewedIdentityAndOnlyAcknowledgedSaveShowsSaved() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY, vm.state.value.page)
        assertEquals("a".repeat(64), vm.state.value.identity?.fingerprint)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(FakeNodeSetup.candidate(), nodes.lastConfirmed)
        assertEquals(OnboardingPage.SAVED, vm.state.value.page)
        assertNull(vm.state.value.identity)
    }

    @Test fun changedKeyAndForgedPageCannotAdvanceConfirmation() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { discoverResult = { ClientResult.Ready(FakeNodeSetup.candidate(true)) } }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        vm.onAction(OnboardingAction.Page(OnboardingPage.SAVED))
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(OnboardingPage.IDENTITY_CHANGED, vm.state.value.page)
        assertEquals(0, nodes.confirmations)
    }

    @Test fun uncertainSaveClearsCandidateAndRequiresReadBeforeAnotherConfirmation() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { confirmResult = failure(ClientOutcome.UNKNOWN) }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(OnboardingPage.SAVE_FAILED, vm.state.value.page)
        assertEquals(OnboardingNoticeKind.UNKNOWN_OUTCOME, vm.state.value.notice?.kind)
        vm.onAction(OnboardingAction.Confirm)
        runCurrent()
        assertEquals(1, nodes.confirmations)
    }

    @Test fun setupAndLockedSelectionAreTypedUnavailableWithoutFakeAccount() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", false, "locked")), null)) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals(OnboardingNoticeKind.UNAVAILABLE, vm.state.value.notice?.kind)
        for (page in listOf(OnboardingPage.CREATE, OnboardingPage.PAIR, OnboardingPage.RECOVER)) {
            vm.onAction(OnboardingAction.Page(page))
            runCurrent()
            assertEquals(page, vm.state.value.page)
            assertEquals(OnboardingNoticeKind.UNAVAILABLE, vm.state.value.notice?.kind)
        }
        assertEquals(3, accounts.setups)
    }

    @Test fun sessionCloseRemovesPrivateRowsAndDropsLateDiscovery() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val nodes = FakeNodeSetup().apply {
            discoverResult = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(FakeNodeSetup.candidate())
            }
        }
        val session = FakeOnboardingSession()
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Private", true, "unlocked")), "a")) }
        }
        val vm = OnboardingViewModel(accounts, nodes, session)
        observe(vm)
        runCurrent()
        assertEquals("Private", vm.state.value.accounts.single().label)
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        session.background()
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.sessionReady)
        assertTrue(vm.state.value.accounts.isEmpty())
        assertNull(vm.state.value.identity)
    }

    @Test fun viewModelClearCancelsLateSelectionAndNavigationEventIsOneShot() = runTest(timeout = 5.seconds) {
        val accounts = FakeAccountAccess().apply {
            listing = { ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", null, true, "unlocked")), "a")) }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        val events = mutableListOf<OnboardingEvent>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        assertEquals(listOf(OnboardingEvent.AccountSelected("a")), events)
        collection.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
        val barrier = CompletableDeferred<Unit>()
        accounts.listing = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", null, true, "unlocked")), "a"))
        }
        val store = ViewModelStore()
        ViewModelProvider.create(store, viewModelFactory { initializer { vm } })[OnboardingViewModel::class]
        vm.onAction(OnboardingAction.SelectAccount("a"))
        runCurrent()
        store.clear()
        barrier.complete(Unit)
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test fun addressCredentialsAreRejectedBeforeDiscovery() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("https://name:password@node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(0, nodes.discoveries)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun discoveryRequiresExplicitOriginConfirmationAndReturnToEditPreservesInput() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup()
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        val entered = "Node.Example/team?source=invite#join"
        vm.onAction(OnboardingAction.Address(entered))
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(0, nodes.discoveries)
        vm.onAction(OnboardingAction.ReviewAddress)
        runCurrent()
        assertEquals(OnboardingPage.CONFIRM, vm.state.value.page)
        assertEquals(entered, vm.state.value.address)
        assertEquals("https://node.example", vm.state.value.normalizedOrigin)
        assertEquals(0, nodes.discoveries)
        vm.onAction(OnboardingAction.Page(OnboardingPage.ADDRESS))
        runCurrent()
        assertEquals(entered, vm.state.value.address)
        assertNull(vm.state.value.normalizedOrigin)
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(1, nodes.discoveries)
        assertEquals("https://node.example", nodes.lastAddress)
    }

    @Test fun typedMalformedDiscoveryFailureIsVisibleAndBusyClears() = runTest(timeout = 5.seconds) {
        val nodes = FakeNodeSetup().apply { discoverResult = { failure() } }
        val vm = OnboardingViewModel(FakeAccountAccess(), nodes, FakeOnboardingSession())
        observe(vm)
        runCurrent()
        vm.onAction(OnboardingAction.Address("node.example"))
        vm.onAction(OnboardingAction.ReviewAddress)
        vm.onAction(OnboardingAction.Discover)
        runCurrent()
        assertEquals(OnboardingPage.CONNECTION_FAILED, vm.state.value.page)
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun immediateMainStartupDoesNotResetGenerationWhileFirstReadIsSuspended() = runTest(timeout = 5.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeAccountAccess().apply {
            listing = {
                barrier.await()
                ClientResult.Ready(AccountCatalog(emptyList(), null))
            }
        }
        val vm = OnboardingViewModel(accounts, FakeNodeSetup(), FakeOnboardingSession())
        observe(vm)
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountsLoaded)
        assertTrue(vm.state.value.nodesLoaded)
        assertFalse(vm.state.value.busy)
    }
}
