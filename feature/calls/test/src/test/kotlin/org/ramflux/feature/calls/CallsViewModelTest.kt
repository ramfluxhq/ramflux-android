// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
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
import org.ramflux.feature.calls.impl.R
@OptIn(ExperimentalCoroutinesApi::class)
class CallsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.load(
        repo: FakeCalls,
        accounts: FakeCallAccounts = FakeCallAccounts(),
        session: FakeCallSession = FakeCallSession()
    ): CallsViewModel {
        val vm = CallsViewModel(repo, accounts, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.onAction(CallsAction.Visible(true))
        runCurrent()
        return vm
    }

    @Test fun signalingUsesRenderedSequenceAndOnlyAuthoritativeTerminalRowEndsCall() = runTest(timeout = 5.seconds) {
        val repo = FakeCalls()
        val vm = load(repo)
        vm.onAction(CallsAction.Select("call"))
        vm.onAction(CallsAction.End("call", 6u, true))
        runCurrent()
        assertTrue(repo.signals.isEmpty())
        vm.onAction(CallsAction.End("call", 7u, true))
        runCurrent()
        assertEquals(callRecord(), repo.signals.single().expected)
        assertFalse(repo.signals.single().operationId.isBlank())
        assertFalse(vm.state.value.selected?.canHangup == true)
        assertEquals(8uL, vm.state.value.selected?.sequence)
    }

    @Test fun everyPublishedTerminalReasonHasResourceBackedHistoryAndSignalPresentation() = runTest(timeout = 5.seconds) {
        val labels = mapOf(
            "completed" to R.string.calls_completed, "cancelled" to R.string.calls_cancelled,
            "answered_elsewhere" to R.string.calls_answered_elsewhere, "busy" to R.string.calls_busy,
            "timeout" to R.string.calls_timeout, "failed" to R.string.calls_failed,
            "missed" to R.string.calls_missed, "declined" to R.string.calls_declined,
            "authority_unavailable" to R.string.calls_authority_unavailable, "authority_refused" to R.string.calls_authority_refused,
            "relay_unavailable" to R.string.calls_relay_unavailable, "media_lease_expired" to R.string.calls_lease_expired,
            "daemon_restarted" to R.string.calls_restarted, "signaling_unavailable" to R.string.calls_signaling_unavailable
        )
        val repo = FakeCalls()
        val vm = load(repo)
        for ((reason, resource) in labels) {
            repo.reading = { ClientResult.Ready(listOf(callRecord(8u, CallPhase.ENDED).copy(endReason = reason))) }
            vm.onAction(CallsAction.Refresh)
            runCurrent()
            assertEquals(reason, uiText(resource), vm.state.value.rows.single().reason)
            repo.reading = { ClientResult.Ready(listOf(callRecord())) }
            repo.signaling = { ClientResult.Ready(it.expected.copy(phase = CallPhase.ENDED, sequence = 8u, endReason = reason)) }
            vm.onAction(CallsAction.Refresh)
            runCurrent()
            vm.onAction(CallsAction.Select("call"))
            vm.onAction(CallsAction.End("call", 7u, true))
            runCurrent()
            assertEquals(reason, uiText(resource), vm.state.value.selected?.reason)
        }
        repo.reading = { ClientResult.Ready(listOf(callRecord(9u, CallPhase.ENDED).copy(endReason = "future"))) }
        vm.onAction(CallsAction.Refresh)
        runCurrent()
        assertEquals(uiText(R.string.calls_unknown_reason), vm.state.value.rows.single().reason)
    }

    @Test fun failedOrUnknownSignalKeepsCurrentRowsAndNoAudioActionCanSignal() = runTest(timeout = 5.seconds) {
        val repo = FakeCalls().apply {
            signaling = { ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 10, ClientOutcome.UNKNOWN)) }
        }
        val vm = load(repo)
        vm.onAction(CallsAction.Audio)
        runCurrent()
        assertNotNull(vm.state.value.unavailable)
        assertTrue(repo.signals.isEmpty())
        vm.onAction(CallsAction.End("call", 7u, true))
        runCurrent()
        assertNotNull(vm.state.value.problem)
        assertEquals(7uL, vm.state.value.rows.single().sequence)
        assertFalse(vm.state.value.fresh)
    }

    @Test fun sdkInvalidationDisablesControlsUntilFreshReadAndCloseConcealsRecords() = runTest(timeout = 5.seconds) {
        val repo = FakeCalls()
        val session = FakeCallSession()
        val vm = load(repo, session = session)
        val barrier = CompletableDeferred<Unit>()
        repo.reading = {
            barrier.await()
            ClientResult.Ready(listOf(callRecord(8u, CallPhase.ENDED)))
        }
        repo.invalidations.emit(ClientResult.Ready(Unit))
        runCurrent()
        assertFalse(vm.state.value.fresh)
        vm.onAction(CallsAction.End("call", 7u, true))
        assertTrue(repo.signals.isEmpty())
        barrier.complete(Unit)
        runCurrent()
        assertEquals(8uL, vm.state.value.rows.single().sequence)
        session.background()
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun accountChangeDuringReadAndLateInvisibleCompletionCannotPublishPrivateRows() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeCallAccounts()
        val repo = FakeCalls().apply {
            reading = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(listOf(callRecord()))
            }
        }
        val vm = load(repo, accounts)
        accounts.result = ClientResult.Ready(AccountCatalog(emptyList(), null))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountRequired)
        assertFalse(vm.state.value.loaded)
        vm.onAction(CallsAction.Visible(false))
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun failedHistoryIsNotAnEmptySuccessAndBackEventDoesNotReplay() = runTest(timeout = 5.seconds) {
        val repo = FakeCalls().apply { reading = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) } }
        val vm = load(repo)
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.loaded)
        val events = mutableListOf<CallsEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(CallsAction.Back)
        runCurrent()
        assertEquals(listOf(CallsEvent.Back), events)
        collector.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
    }
}
