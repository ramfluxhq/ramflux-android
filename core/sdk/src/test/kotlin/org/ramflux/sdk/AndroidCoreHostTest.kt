// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidCoreHostTest {
    @Test fun foregroundSharesOneCoreAndBackgroundClosesIt() = runTest(timeout = 5.seconds) {
        val native = FakeAbi()
        val host = AndroidCoreHost(native, "/private/no_backup", "a".repeat(64))
        val first = host.foreground() as CoreResult.Success
        val second = host.foreground() as CoreResult.Success
        assertSame(first.value, second.value); assertEquals(1, native.liveWriters)
        assertTrue(host.background() is CoreResult.Success)
        assertEquals(0, native.liveWriters); assertEquals(HostState.Stopped, host.state.value)
    }

    @Test fun cancelledOpenRetainsItsUndeliveredHandleUntilShutdownCompletes() = runTest(timeout = 5.seconds) {
        val native = FakeAbi().apply { closeTimeout = true }
        val call = async(start = CoroutineStart.LAZY) {
            CoreClient.open(native, "/test/cancelled-open", "a".repeat(64), StandardTestDispatcher(testScheduler))
        }
        native.onOpened = { call.cancel() }
        call.start(); runCurrent()
        assertEquals(1, native.liveWriters)
        assertFalse(call.isCompleted)
        advanceTimeBy(101); runCurrent()
        assertFalse(call.isCompleted)
        native.releaseTimedOutOwner()
        advanceTimeBy(101); runCurrent(); call.join()
        assertEquals(0, native.liveWriters)
        assertTrue(call.isCancelled)
    }

    @Test fun timeoutThenLeaseBusyThenReleasedLeaseRecoversWithoutTwoWriters() = runTest(timeout = 5.seconds) {
        val native = FakeAbi()
        val root = "/private/no_backup"
        val host = AndroidCoreHost(native, root, "a".repeat(64))
        assertTrue(host.foreground() is CoreResult.Success)
        native.closeTimeout = true
        assertEquals(10, (host.background() as CoreResult.Failure).error.code)
        assertEquals(1, native.liveWriters)
        assertEquals(24, (host.foreground() as CoreResult.Failure).error.code)
        assertEquals(1, native.liveWriters)
        native.releaseTimedOutOwner()
        val competitor = native.newClient(root)
        assertEquals(18, (host.foreground() as CoreResult.Failure).error.code)
        assertEquals(1, native.liveWriters)
        native.close(competitor)
        assertTrue(host.foreground() is CoreResult.Success)
        assertEquals(1, native.liveWriters)
        assertTrue(host.state.value is HostState.Running)
        host.background()
    }
}
