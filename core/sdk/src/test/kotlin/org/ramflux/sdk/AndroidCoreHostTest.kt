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
}
