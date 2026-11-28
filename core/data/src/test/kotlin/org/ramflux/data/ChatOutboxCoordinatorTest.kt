// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatOutboxCoordinatorTest {
    private class Store : ChatOutboxRepository {
        val stored = mutableMapOf<String, List<QueuedSend>>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(account: String, conversation: String) = stored["$account/$conversation"].orEmpty()
        override suspend fun save(account: String, conversation: String, sends: List<QueuedSend>): ClientResult<Unit> {
            gate?.await()
            if (sends.isEmpty()) stored.remove("$account/$conversation") else stored["$account/$conversation"] = sends
            return ClientResult.Ready(Unit)
        }
        override suspend fun clearAccount(account: String) {
            stored.keys.removeAll { it.startsWith("$account/") }
        }
    }

    private fun refused() = ClientResult.Failed(
        ClientProblem(ClientFailureKind.FAILED, 4, ClientOutcome.REFUSED, statusName = SendBlock.IDENTITY_CHANGE_PENDING)
    )

    private fun accepted(): ClientResult<SendReceipt> = ClientResult.Ready(SendReceipt("sent"))
    private fun row(id: String = "l-1", operation: String = "op-1", blocked: Boolean = false) = QueuedSend(id, operation, "t", 1L, blocked = blocked)

    private fun TestScope.coordinator(store: Store) = ChatOutboxCoordinator(store, backgroundScope)

    @Test fun aRefusedSendMarksTheStoredRowBlockedAndALaterUnblockedPersistKeepsIt() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        val result = coordinator.send("a", "c", "l-1", "op-1") { refused() }
        assertTrue(result is ClientResult.Failed)
        assertTrue(store.stored["a/c"].orEmpty().single().blocked)
        coordinator.persist("a", "c", listOf(row(blocked = false)))
        runCurrent()
        assertTrue("a stale unblocked snapshot never undoes the refusal", store.stored["a/c"].orEmpty().single().blocked)
    }

    @Test fun aSecondSendWithARefusedKeyReturnsTheRefusalWithoutCalling() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        var calls = 0
        coordinator.send("a", "c", "l-1", "op-1") { calls++; refused() }
        val again = coordinator.send("a", "c", "l-1", "op-1") { calls++; accepted() }
        assertEquals(1, calls)
        assertEquals(SendBlock.IDENTITY_CHANGE_PENDING, (again as ClientResult.Failed).problem.statusName)
        assertEquals(ClientOutcome.REFUSED, again.problem.outcome)
    }

    @Test fun twoConcurrentSendsWithTheSameKeyInvokeTheCallOnce() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.send("a", "c", "l-1", "op-1") { calls++; gate.await(); refused() } }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.send("a", "c", "l-1", "op-1") { calls++; gate.await(); refused() } }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(first.await() is ClientResult.Failed)
        assertTrue(second.await() is ClientResult.Failed)
    }

    @Test fun cancellingTheCallerDuringTheCallStillRecordsTheRefusal() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        val gate = CompletableDeferred<Unit>()
        val caller = launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.send("a", "c", "l-1", "op-1") { gate.await(); refused() } }
        runCurrent()
        caller.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertTrue(store.stored["a/c"].orEmpty().single().blocked)
    }

    @Test fun aCancelledCallRecordsNothing() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        val gate = CompletableDeferred<Unit>()
        val caller = launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.send("a", "c", "l-1", "op-1") { gate.await(); refused() } }
        runCurrent()
        gate.cancel()
        runCurrent()
        caller.cancelAndJoin()
        assertFalse(store.stored["a/c"].orEmpty().single().blocked)
        var calls = 0
        coordinator.send("a", "c", "l-1", "op-1") { calls++; accepted() }
        assertEquals("no refusal was seen, so the key is free", 1, calls)
    }

    @Test fun aNewCoordinatorOverABlockedRowLoadsItBlockedAndRefusesItsKey() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row(blocked = true)) }
        val coordinator = coordinator(store)
        assertTrue(coordinator.load("a", "c").single().blocked)
        var calls = 0
        val result = coordinator.send("a", "c", "l-1", "op-1") { calls++; accepted() }
        assertEquals(0, calls)
        assertTrue(result is ClientResult.Failed)
    }

    @Test fun theSameRowIdWithANewOperationIsNotForcedBlocked() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        coordinator.send("a", "c", "l-1", "op-1") { refused() }
        coordinator.persist("a", "c", listOf(row(operation = "op-2")))
        runCurrent()
        val saved = store.stored["a/c"].orEmpty().single()
        assertEquals("op-2", saved.operation)
        assertFalse(saved.blocked)
        var calls = 0
        coordinator.send("a", "c", "l-1", "op-2") { calls++; accepted() }
        assertEquals(1, calls)
    }

    @Test fun aRefusalWithNoStoredRowWritesNothingButStillGuardsReplays() = runTest(timeout = 5.seconds) {
        val store = Store()
        val coordinator = coordinator(store)
        coordinator.send("a", "c", "l-1", "op-1") { refused() }
        assertTrue(store.stored.isEmpty())
        var calls = 0
        coordinator.send("a", "c", "l-1", "op-1") { calls++; accepted() }
        assertEquals(0, calls)
    }

    @Test fun clearAccountEmptiesTheStoreAndDropsTheRefusedKeys() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()); stored["b/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        coordinator.send("a", "c", "l-1", "op-1") { refused() }
        coordinator.clearAccount("a")
        assertTrue(store.stored["a/c"] == null)
        assertEquals(1, store.stored["b/c"].orEmpty().size)
        var calls = 0
        coordinator.send("a", "c", "l-1", "op-1") { calls++; accepted() }
        assertEquals(1, calls)
    }

    @Test fun aSuspendedStoreSaveSerialisesLoadBehindTheRefusalWrite() = runTest(timeout = 5.seconds) {
        val store = Store().apply { stored["a/c"] = listOf(row()) }
        val coordinator = coordinator(store)
        store.gate = CompletableDeferred()
        val sending = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.send("a", "c", "l-1", "op-1") { refused() } }
        runCurrent()
        val loading = async(UnconfinedTestDispatcher(testScheduler)) { coordinator.load("a", "c") }
        runCurrent()
        assertFalse("load waits for the refusal write", loading.isCompleted)
        store.gate?.complete(Unit)
        runCurrent()
        sending.await()
        assertTrue(loading.await().single().blocked)
    }
}
