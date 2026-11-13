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
    private fun request(method: String = "node.list") = CoreRequest(method, "node", "A", "original", empty, Long.MAX_VALUE)

    @Test fun replyOomRetainsOperationAfterBoundedCleanupWithoutClosingClient() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.submitReplyOom = true; abi.releaseFailures = 99
        var failed = false
        try { client.execute(request()) } catch (_: OutOfMemoryError) { failed = true }
        assertTrue("the original allocation failure reaches the caller", failed)
        assertEquals("exactly five bounded cleanup attempts", 5, abi.cancelled.size)
        assertEquals("client must remain open", 0, abi.closes)
        assertEquals(1, abi.client(id).operations.size)
        assertTrue(client.cleanupFailure.value is CoreError.CleanupPending)
        abi.releaseFailures = 0
        assertTrue(client.resetEventsAfterSnapshot(client.subscriptionGeneration.value) is CoreResult.Success)
        assertTrue(abi.client(id).operations.isEmpty())
        assertNull(client.cleanupFailure.value)
        assertTrue(client.execute(request()) is CoreResult.Success)
        client.close()
    }

    @Test fun replyOomCleanupBackoffAllowsAnotherRequestThroughGuard() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.submitReplyOom = true; abi.cancelFailures = 2
        val failed = async { try { client.execute(request()); false } catch (_: OutOfMemoryError) { true } }
        runCurrent()
        assertFalse(failed.isCompleted)
        val other = async { client.execute(request()) }
        runCurrent()
        assertEquals("another request is admitted while cleanup is backing off", 2, abi.submitted.size)
        assertEquals(0, abi.closes)
        advanceUntilIdle()
        assertTrue(failed.await()); assertTrue(other.await() is CoreResult.Success)
        assertTrue(abi.client(id).operations.isEmpty())
        client.close()
    }

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
        assertEquals(CoreUpdate.Terminal("resync_required", null, client.subscriptionGeneration.value), rows.single())
        assertEquals(27, abi.eventPoll(id).code)
        assertTrue(client.resetEventsAfterSnapshot(client.subscriptionGeneration.value) is CoreResult.Success)
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
            if (row is CoreUpdate.Terminal) reset = client.resetEventsAfterSnapshot(client.subscriptionGeneration.value) is CoreResult.Success
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

    @Test fun lateSameAccountFramesAndStaleResetCannotCrossSubscriptionGeneration() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi(); val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        abi.onSubscription = { core, _ -> abi.event(core, "A", "first") }
        val first = client.updates(SubscriptionFilter("A", empty)).take(1).toList().single() as CoreUpdate.Events
        val old = first.subscriptionGeneration; first.body.close(); advanceUntilIdle()
        abi.onSubscription = { core, _ ->
            abi.event(core, "A", "late-old", generation = old)
            abi.event(core, "A", "new-only")
        }
        val second = client.updates(SubscriptionFilter("A", empty)).take(1).toList().single() as CoreUpdate.Events
        assertNotEquals(old, second.subscriptionGeneration)
        second.body.useOnce { assertTrue(it.decodeToString().contains("new-only")); assertFalse(it.decodeToString().contains("late-old")) }
        advanceUntilIdle()
        abi.event(id, "A", "keep-new-queue")
        val stale = client.resetEventsAfterSnapshot(old) as CoreResult.Failure
        assertEquals(1, stale.error.code)
        val queued = abi.eventPoll(id)
        assertTrue(queued.body.decodeToString().contains("keep-new-queue")); queued.body.fill(0)
        assertTrue(client.resetEventsAfterSnapshot(second.subscriptionGeneration) is CoreResult.Success)
        client.close()
    }

    @Test fun cancelledOpenCanRecollectWithoutKnowingUndeliveredGeneration() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        val client = CoreClient(abi, id, StandardTestDispatcher(testScheduler))
        lateinit var first: Job
        abi.onSubscription = { core, token -> abi.finish(core, token); first.cancel() }
        first = launch { client.updates(SubscriptionFilter("A", empty)).collect() }
        runCurrent(); first.join()
        assertEquals(0uL, client.subscriptionGeneration.value)
        assertTrue(abi.client(id).generation != 0uL)
        abi.pendingRequests = false
        abi.onSubscription = { core, _ -> abi.event(core, "A", "recollected") }
        val row = client.updates(SubscriptionFilter("A", empty)).take(1).toList().single() as CoreUpdate.Events
        row.body.useOnce { assertTrue(it.decodeToString().contains("recollected")) }
        client.close()
    }

    @Test fun currentFramesQueuedBeforeOpenPollCompletesAreNotDrained() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        var firstPoll = true
        val seam = object : NativeCore by abi {
            override fun operationPoll(core: Long, operation: Long): NativeReply {
                if (firstPoll) { firstPoll = false; return NativeReply(24, byteArrayOf()) }
                return abi.operationPoll(core, operation)
            }
        }
        val client = CoreClient(seam, id, StandardTestDispatcher(testScheduler))
        abi.onSubscription = { core, token -> abi.finish(core, token); abi.event(core, "A", "early-current") }
        val row = client.updates(SubscriptionFilter("A", empty)).take(1).toList().single() as CoreUpdate.Events
        row.body.useOnce { assertTrue(it.decodeToString().contains("early-current")) }
        client.close()
    }

    @Test fun admittedDetachedWorkRetainsNativeCreditUntilTerminal() {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        val operation = abi.submit(id, CoreClient.encodeRequest(request()))
        abi.admit(id, operation.handle)
        assertEquals(0, abi.operationRelease(id, operation.handle))
        assertEquals(24, abi.operationPoll(id, operation.handle).code)
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
        abi.close(id); assertEquals(1, abi.eventPoll(id).code)
    }
}
