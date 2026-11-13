// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class CoreClientTest {
    private val empty = JsonObject(emptyMap())
    private fun request(method: String = "node.list") = CoreRequest(method, "node", "A", "original", empty)

    @Test fun publicExecuteRejectsAllSubscriptionMethodsAndSecretMethods() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient(); val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        for (method in listOf("subscription.open", "bus.subscribe", "subscription.close", "account.create.show_once", "account.unlock")) {
            val result = client.execute(request(method)) as CoreResult.Failure
            assertEquals(Effect.NONE, result.error.effect)
        }
        assertTrue(abi.submitted.isEmpty()); client.close()
    }

    @Test fun cancellationCancelsAndReleasesUniqueOperationWithoutReissuing() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        val call = async { client.execute(request()) }; runCurrent(); call.cancelAndJoin()
        assertEquals(1, abi.submitted.size); assertEquals(1, abi.released.size)
        assertEquals(1, abi.cancelled.distinct().size); assertTrue(abi.client(id).operations.isEmpty()); client.close()
    }

    @Test fun immediateCapacityRefusalHasNoEffect() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        repeat(64) { abi.submit(id, CoreClient.encodeRequest(request())) }
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        val result = client.execute(request()) as CoreResult.Failure
        assertEquals(15, result.error.code); assertEquals(Effect.NONE, result.error.effect)
        assertEquals("original", result.operationId); client.close()
    }

    @Test fun streamHoldsTokenUntilEndAndCanRecollectImmediately() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient(); val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        val received = mutableListOf<CoreUpdate>()
        val first = launch { client.updates(SubscriptionFilter("A", empty)).collect { row ->
            received.add(row); if (row is CoreUpdate.Events) row.body.close()
        } }
        runCurrent()
        val token = abi.client(id).subscription
        assertNotNull(token); assertTrue(abi.released.isEmpty())
        abi.event(id, "A", "first"); advanceTimeBy(101); runCurrent()
        assertEquals(1, received.size); assertEquals(token, abi.client(id).subscription)
        first.cancel()
        abi.onSubscription = { core, _ -> abi.event(core, "A", "second") }
        val second = async { client.updates(SubscriptionFilter("A", empty)).take(1).toList() }
        val rows = second.await(); (rows.single() as CoreUpdate.Events).body.close()
        first.join(); runCurrent()
        assertNull(abi.client(id).subscription); assertEquals(2, abi.released.size)
        client.close()
    }

    @Test fun cleanupRetriesBackpressureBeforeYieldingSubscriptionOwnership() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        abi.onSubscription = { core, _ -> abi.event(core, "A", "one") }
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.cancelFailures = 2
        val rows = client.updates(SubscriptionFilter("A", empty)).take(1).toList()
        (rows.single() as CoreUpdate.Events).body.close()
        advanceUntilIdle()
        assertTrue(abi.cancelled.size >= 3); assertNull(abi.client(id).subscription)
        assertTrue(abi.client(id).operations.isEmpty()); assertNull(client.cleanupFailure.value); client.close()
    }

    @Test fun exhaustedCleanupStaysVisibleAndRetainedUntilRepair() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.releaseFailures = 99
        assertTrue(client.execute(request()) is CoreResult.Success)
        assertTrue(client.cleanupFailure.value is CoreError.CleanupPending)
        assertEquals(1, abi.client(id).operations.size)
        abi.releaseFailures = 0
        abi.onSubscription = { core, _ -> abi.event(core, "A", "repaired") }
        val rows = client.updates(SubscriptionFilter("A", empty)).take(1).toList()
        (rows.single() as CoreUpdate.Events).body.close()
        advanceUntilIdle()
        assertTrue(abi.client(id).operations.isEmpty()); assertNull(client.cleanupFailure.value)
        client.close()
    }

    @Test fun overflowLatchesUntilResetThenResubscriptionWorks() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient(); val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.onSubscription = { core, _ -> repeat(65) { abi.event(core, "A", "overflow") } }
        val rows = client.updates(SubscriptionFilter("A", empty)).toList()
        assertEquals(CoreUpdate.Terminal("resync_required", null), rows.single())
        assertEquals(27, abi.eventPoll(id).code)
        assertTrue(client.resetEventsAfterSnapshot() is CoreResult.Success)
        assertEquals(24, abi.eventPoll(id).code)
        abi.onSubscription = { core, _ -> abi.event(core, "A", "after-reset") }
        val resumed = client.updates(SubscriptionFilter("A", empty)).take(1).toList()
        (resumed.single() as CoreUpdate.Events).body.close(); client.close()
    }

    @Test fun resetCanRunImmediatelyFromTerminalCollector() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.onSubscription = { core, _ -> repeat(65) { abi.event(core, "A", "overflow") } }
        var reset = false
        client.updates(SubscriptionFilter("A", empty)).collect { row ->
            if (row is CoreUpdate.Terminal) reset = client.resetEventsAfterSnapshot() is CoreResult.Success
        }
        assertTrue(reset); assertEquals(24, abi.eventPoll(id).code)
        client.close()
    }

    @Test fun oldAccountFramesCannotCrossIntoNewSubscription() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient(); val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.event(id, "A", "queued-before-open")
        abi.onSubscription = { core, _ -> abi.event(core, "A", "late-old-account"); abi.event(core, "B", "new-account") }
        val rows = client.updates(SubscriptionFilter("B", empty)).take(1).toList()
        (rows.single() as CoreUpdate.Events).body.useOnce { raw ->
            assertTrue(raw.decodeToString().contains("new-account")); assertFalse(raw.decodeToString().contains("old-account"))
        }
        client.close()
    }

    @Test fun admittedDetachedWorkRetainsNativeCreditUntilTerminal() {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        val operation = abi.submit(id, CoreClient.encodeRequest(request()))
        abi.admit(id, operation.handle)
        assertEquals(0, abi.operationRelease(id, operation.handle))
        assertEquals(1, abi.operationPoll(id, operation.handle).code)
        assertEquals(1, abi.client(id).operations.size)
        abi.finish(id, operation.handle)
        assertTrue(abi.client(id).operations.isEmpty())
        abi.close(id)
    }

    @Test fun fixtureRejectsDuplicateReleasedAndClosedHandles() {
        val abi = FakeAbi(); val id = abi.newClient()
        val a = abi.submit(id, CoreClient.encodeRequest(request("subscription.open")))
        assertEquals(15, abi.submit(id, CoreClient.encodeRequest(request("subscription.open"))).code)
        assertEquals(0, abi.operationRelease(id, a.handle))
        assertEquals(1, abi.operationPoll(id, a.handle).code)
        assertEquals(1, abi.operationRelease(id, a.handle))
        val b = abi.submit(id, CoreClient.encodeRequest(request("subscription.open")))
        assertNotEquals(a.handle, b.handle)
        abi.close(id); assertEquals(26, abi.eventPoll(id).code)
    }
}
