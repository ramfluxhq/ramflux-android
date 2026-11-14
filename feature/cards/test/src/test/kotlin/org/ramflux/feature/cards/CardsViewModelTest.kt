// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
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
import org.ramflux.designsystem.uiText
import org.ramflux.feature.cards.impl.R
@OptIn(ExperimentalCoroutinesApi::class)
class CardsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.load(repo: FakeCardHost, session: FakeCardSession = FakeCardSession()): CardsViewModel {
        val vm = CardsViewModel(repo, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        vm.onAction(CardsAction.Visible(true))
        runCurrent()
        return vm
    }

    @Test fun unavailableIsTypedAndUnexpectedReadyCannotOpenOrAuthorizeContent() = runTest(timeout = 5.seconds) {
        val repo = FakeCardHost()
        val vm = load(repo)
        assertEquals(uiText(R.string.cards_missing), vm.state.value.unavailable)
        assertTrue(vm.state.value.canCheck)
        val events = mutableListOf<CardsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        repo.checking = { ClientResult.Ready(Unit) }
        vm.onAction(CardsAction.Refresh)
        runCurrent()
        assertEquals(uiText(R.string.cards_unverified), vm.state.value.problem)
        assertNull(vm.state.value.unavailable)
        assertTrue(events.isEmpty())
    }

    @Test fun failedCheckDiffersFromUnavailableAndDuplicateRefreshDoesNotStartAnotherCheck() = runTest(timeout = 5.seconds) {
        val repo = FakeCardHost()
        val vm = load(repo)
        val barrier = CompletableDeferred<Unit>()
        repo.checking = {
            barrier.await()
            ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
        }
        vm.onAction(CardsAction.Refresh)
        runCurrent()
        val calls = repo.calls
        vm.onAction(CardsAction.Refresh)
        runCurrent()
        assertEquals(calls, repo.calls)
        barrier.complete(Unit)
        runCurrent()
        assertNotNull(vm.state.value.problem)
        assertNull(vm.state.value.unavailable)
    }

    @Test fun closedSessionNeverChecksAndLateInvisibleResultCannotRestoreState() = runTest(timeout = 5.seconds) {
        val repo = FakeCardHost()
        val session = FakeCardSession()
        session.background()
        val vm = load(repo, session)
        assertEquals(0, repo.calls)
        assertTrue(vm.state.value.closed)
        val barrier = CompletableDeferred<Unit>()
        repo.checking = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
        session.foreground()
        runCurrent()
        vm.onAction(CardsAction.Visible(false))
        barrier.complete(Unit)
        runCurrent()
        assertNull(vm.state.value.problem)
        assertFalse(vm.state.value.checking)
        val count = repo.calls
        session.foreground()
        runCurrent()
        assertEquals(count, repo.calls)
    }

    @Test fun backIsOneShotAndCancelsOwnedWork() = runTest(timeout = 5.seconds) {
        val repo = FakeCardHost()
        val vm = load(repo)
        val events = mutableListOf<CardsEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(CardsAction.Back)
        runCurrent()
        collector.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(listOf(CardsEvent.Back), events)
        assertFalse(vm.state.value.checking)
    }
}
