// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.ramflux.data.CallDirection
import org.ramflux.data.CallPhase
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSnapshot

@OptIn(ExperimentalCoroutinesApi::class)
class CallSessionViewModelTest {
    @Before fun main() = Dispatchers.setMain(StandardTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun TestScope.observe(vm: CallSessionViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    @Test fun anOutgoingCallAsksForTheMicrophoneThenRingsAndSaysMediaIsUnavailable() = runTest(timeout = 5.seconds) {
        val calls = FakeCalls()
        val vm = CallSessionViewModel(calls, FakeCallSession(), RefusingCallMediaEngine())
        observe(vm)
        vm.onAction(CallSessionAction.Load("a", "conversation", null, microphoneGranted = false))
        runCurrent()
        assertEquals(CallStage.PERMISSION, vm.state.value.stage)
        assertTrue(calls.invites.isEmpty())
        vm.onAction(CallSessionAction.Load("a", "conversation", null, microphoneGranted = true))
        runCurrent()
        assertEquals(listOf("conversation"), calls.invites)
        assertEquals(CallStage.OUTGOING, vm.state.value.stage)
        // The peer answers: the record goes active, the engine refuses, and the screen says so.
        val active = callRecord(sequence = 8u, phase = CallPhase.ACTIVE).copy(direction = CallDirection.OUTGOING)
        calls.reading = { ClientResult.Ready(listOf(active)) }
        calls.invalidations.tryEmit(ClientResult.Ready(Unit))
        runCurrent()
        assertEquals(CallStage.IN_CALL, vm.state.value.stage)
        assertTrue(vm.state.value.mediaUnavailable)
        vm.onAction(CallSessionAction.HangUp)
        runCurrent()
        assertFalse(calls.signals.single().decline)
        assertEquals(CallStage.ENDED, vm.state.value.stage)
    }

    @Test fun anIncomingCallCanBeAnsweredOrDeclined() = runTest(timeout = 5.seconds) {
        val calls = FakeCalls()
        val vm = CallSessionViewModel(calls, FakeCallSession(), RefusingCallMediaEngine())
        observe(vm)
        vm.onAction(CallSessionAction.Load("a", null, "call", microphoneGranted = false))
        runCurrent()
        assertEquals(CallStage.INCOMING, vm.state.value.stage)
        vm.onAction(CallSessionAction.Answer)
        runCurrent()
        assertEquals(1, calls.answers.size)
        assertEquals(CallStage.CONNECTING, vm.state.value.stage)
        assertTrue(vm.state.value.mediaUnavailable)
        val other = CallSessionViewModel(FakeCalls(), FakeCallSession(), RefusingCallMediaEngine())
        observe(other)
        other.onAction(CallSessionAction.Load("a", null, "call", microphoneGranted = true))
        runCurrent()
        other.onAction(CallSessionAction.Decline)
        runCurrent()
        assertEquals(CallStage.ENDED, other.state.value.stage)
    }

    @Test fun offlineFailureAndALostSessionAreTheirOwnStates() = runTest(timeout = 5.seconds) {
        val calls = FakeCalls().apply {
            inviting = { ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE)) }
        }
        val session = FakeCallSession()
        val vm = CallSessionViewModel(calls, session, RefusingCallMediaEngine())
        observe(vm)
        vm.onAction(CallSessionAction.Load("a", "conversation", null, microphoneGranted = true))
        runCurrent()
        assertEquals(CallStage.OFFLINE, vm.state.value.stage)
        calls.inviting = {
            ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.REFUSED, statusName = "account busy")
            )
        }
        vm.onAction(CallSessionAction.Retry)
        runCurrent()
        assertEquals(CallStage.FAILED, vm.state.value.stage)
        session.status.value = ClientSnapshot(ClientPhase.CLOSED, 2)
        runCurrent()
        assertEquals(CallStage.CONNECTION_LOST, vm.state.value.stage)
    }
}
