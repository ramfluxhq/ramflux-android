// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class EventHubTest {
    /** A scripted native lane: each open gets its own channel, as `CoreClient.updates` does. */
    private class FakeLane {
        val opened = mutableListOf<SubscriptionFilter>()
        val channels = mutableListOf<Channel<CoreUpdate>>()
        var closedCount = 0
        val resets = mutableListOf<ULong>()
        fun open(filter: SubscriptionFilter): Flow<CoreUpdate> {
            opened += filter
            val channel = Channel<CoreUpdate>(Channel.UNLIMITED)
            channels += channel
            return channel.receiveAsFlow().onCompletion { closedCount++ }
        }
        val current get() = channels.last()
        fun topics(index: Int = opened.lastIndex) = opened[index].body["topics"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    private fun event(topic: String, generation: ULong = 1uL) = CoreUpdate.Events(
        SecretReply(SecretBytes.take("""{"method":"$topic","body":{"x":1}}""".toByteArray())), generation,
    )

    private fun filter(account: String, vararg topics: String) =
        SubscriptionFilter(account, buildJsonObject { put("topics", JsonArray(topics.map(::JsonPrimitive))) })

    private fun TestScope.hub(lane: FakeLane, capacity: Int = 4) = EventHub(
        lane::open,
        { generation -> lane.resets += generation; CoreResult.Success(JsonObject(emptyMap())) },
        CoroutineScope(StandardTestDispatcher(testScheduler)),
        capacity,
    )

    private fun CoroutineScope.collectInto(hub: EventHub, filter: SubscriptionFilter, sink: MutableList<CoreUpdate>) =
        launch { hub.subscribe(filter).collect { sink += it } }

    private fun MutableList<CoreUpdate>.topics() = filterIsInstance<CoreUpdate.Events>().map { update ->
        update.body.useOnce { String(it) }.substringAfter("\"method\":\"").substringBefore('"')
    }

    @Test fun twoCollectorsShareOneNativeSubscriptionAndEachOwnsItsCopy() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val list = mutableListOf<CoreUpdate>(); val chat = mutableListOf<CoreUpdate>()
        val a = collectInto(hub, filter("A", "*"), list)
        val b = collectInto(hub, filter("A", "*"), chat)
        runCurrent()
        assertEquals("one native subscription for both panes", 1, lane.opened.size)
        assertEquals("the app subscribes as an attended frontend", true, lane.opened.single().body["attended_frontend"]?.jsonPrimitive?.boolean)
        lane.current.send(event("message.received")); runCurrent()
        assertEquals(listOf("message.received"), list.topics())
        assertEquals(listOf("message.received"), chat.topics())
        a.cancel(); b.cancel(); advanceUntilIdle()
        assertEquals(0, hub.collectorCount)
        assertEquals("the last collector leaving cancels the native subscription", 1, lane.closedCount)
    }

    @Test fun aSlowCollectorGetsItsOwnResyncAndNeverStallsTheOthers() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane, capacity = 4)
        val fast = mutableListOf<CoreUpdate>()
        val slowSubscriber = hub.subscribe(filter("A", "*"))
        val slowChannel = Channel<CoreUpdate>(Channel.RENDEZVOUS)
        val slow = launch { slowSubscriber.collect { slowChannel.send(it) } }
        val quick = collectInto(hub, filter("A", "*"), fast)
        runCurrent()
        repeat(20) { lane.current.send(event("message.received")); runCurrent() }
        assertEquals("the fast pane received every event", 20, fast.topics().size)
        val drained = mutableListOf<CoreUpdate>()
        while (true) drained += slowChannel.tryReceive().getOrNull() ?: run { runCurrent(); slowChannel.tryReceive().getOrNull() } ?: break
        assertTrue("the slow pane is told to reload", drained.any { it is CoreUpdate.Terminal && it.kind == "resync_required" })
        assertTrue("the slow pane never buffers beyond its bound", drained.size <= 4 + 2)
        assertEquals(1, lane.opened.size)
        assertTrue(lane.resets.isEmpty())
        slow.cancel(); quick.cancel(); advanceUntilIdle()
    }

    @Test fun nativeOverflowResetsOnceTellsEveryCollectorAndReopens() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val list = mutableListOf<CoreUpdate>(); val chat = mutableListOf<CoreUpdate>()
        val a = collectInto(hub, filter("A", "*"), list)
        val b = collectInto(hub, filter("A", "*"), chat)
        runCurrent()
        lane.current.send(CoreUpdate.Terminal("resync_required", null, 7uL)); lane.current.close()
        advanceUntilIdle()
        assertEquals(listOf(7uL), lane.resets)
        assertEquals("reopened after the reset", 2, lane.opened.size)
        assertTrue(list.single() is CoreUpdate.Terminal); assertTrue(chat.single() is CoreUpdate.Terminal)
        lane.current.send(event("message.updated", 8uL)); runCurrent()
        assertEquals("collectors stay registered across the reopen", listOf("message.updated"), list.topics())
        assertEquals(listOf("message.updated"), chat.topics())
        a.cancel(); b.cancel(); advanceUntilIdle()
    }

    @Test fun anInBandLagFrameReopensWithoutANativeReset() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val sink = mutableListOf<CoreUpdate>()
        val job = collectInto(hub, filter("A", "*"), sink)
        runCurrent()
        lane.current.send(event("stream.lagged")); advanceUntilIdle()
        assertEquals(2, lane.opened.size)
        assertTrue(lane.resets.isEmpty())
        assertTrue(sink.single() is CoreUpdate.Terminal)
        job.cancel(); advanceUntilIdle()
    }

    @Test fun topicsFilterPerCollectorAndANewTopicReopensWithTheUnion() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val tasks = mutableListOf<CoreUpdate>(); val typing = mutableListOf<CoreUpdate>()
        val a = collectInto(hub, filter("A", "task.changed"), tasks)
        runCurrent()
        assertEquals(listOf("task.changed"), lane.topics())
        val b = collectInto(hub, filter("A", "message.transient"), typing)
        advanceUntilIdle()
        assertEquals(2, lane.opened.size)
        assertEquals(listOf("message.transient", "task.changed"), lane.topics())
        assertTrue("the existing collector reloads after the reopen", tasks.single() is CoreUpdate.Terminal)
        lane.current.send(event("task.changed")); lane.current.send(event("message.transient")); runCurrent()
        assertEquals(listOf("task.changed"), tasks.topics())
        assertEquals(listOf("message.transient"), typing.topics())
        val c = collectInto(hub, filter("A", "task.changed"), mutableListOf())
        runCurrent()
        assertEquals("a covered collector does not reopen", 2, lane.opened.size)
        a.cancel(); b.cancel(); c.cancel(); advanceUntilIdle()
    }

    @Test fun aCollectorOfAnotherAccountReplacesTheSubscription() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val old = mutableListOf<CoreUpdate>()
        val a = collectInto(hub, filter("A", "*"), old)
        runCurrent()
        val b = collectInto(hub, filter("B", "*"), mutableListOf())
        advanceUntilIdle()
        assertTrue((old.single() as CoreUpdate.Failure).error is CoreError.Closed)
        assertTrue("the old collector ended", a.isCompleted)
        assertEquals("B", lane.opened.last().accountId)
        b.cancel(); advanceUntilIdle()
    }

    @Test fun aFailureReachesEveryCollectorAndEndsThem() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val sink = mutableListOf<CoreUpdate>()
        val job = collectInto(hub, filter("A", "*"), sink)
        runCurrent()
        lane.current.send(CoreUpdate.Failure(CoreError.Closed())); advanceUntilIdle()
        assertTrue(sink.single() is CoreUpdate.Failure)
        assertTrue(job.isCompleted)
        assertEquals(0, hub.collectorCount)
    }

    @Test fun aFilterWithACursorIsRefused() = runTest(timeout = 5.seconds) {
        val lane = FakeLane(); val hub = hub(lane)
        val sink = mutableListOf<CoreUpdate>()
        collectInto(hub, SubscriptionFilter("A", buildJsonObject { put("since_seq", 3) }), sink)
        advanceUntilIdle()
        assertTrue(sink.single() is CoreUpdate.Failure)
        assertTrue(lane.opened.isEmpty())
    }
}
