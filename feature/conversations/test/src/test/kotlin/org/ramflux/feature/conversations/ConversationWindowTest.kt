// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.ramflux.data.*

/** Windowed paging, live changes and the 50,000-row synthetic directory. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWindowTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    private fun row(index: Int, activity: Long? = index.toLong()) =
        ConversationSummary("c" + index.toString().padStart(6, '0'), "Title $index", (index % 7).toULong(), false, index % 7 != 0, activity)

    /** Pages by id like the SDK: 100 rows per page, activity unrelated to id order. */
    private fun synthetic(total: Int): suspend (String?) -> ClientResult<ConversationPage> = { cursor ->
        val start = cursor?.toInt() ?: 0
        val end = minOf(start + 100, total)
        val rows = (start until end).map { row(it, ((it.toLong() * 7_919) % total)) }
        ClientResult.Ready(ConversationPage(rows, if (end < total) end.toString() else null))
    }

    private fun TestScope.model(directory: FakeConversationDirectory) =
        ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession()).also {
            it.work = UnconfinedTestDispatcher(testScheduler)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.state.collect() }
        }

    @Test fun windowIsRecentFirstAndEvictsTheLeastRecentBeyondCapacity() {
        val window = ConversationWindow(capacity = 3)
        assertEquals(2, window.merge(listOf(row(1, 10), row(2, null))))
        assertEquals(listOf("c000001", "c000002"), window.ordered().map { it.id })
        assertEquals(2, window.merge(listOf(row(3, 30), row(4, 20))))
        assertEquals(listOf("c000003", "c000004", "c000001"), window.ordered().map { it.id })
        assertFalse("c000002" in window)
        assertEquals(0, window.merge(listOf(row(5, 1))))
        window.upsert(row(1, 99).copy(title = "c000001"), titled = false)
        assertEquals("Title 1", window.ordered().first().title)
        window.remove("c000003")
        assertEquals(listOf("c000001", "c000004"), window.ordered().map { it.id })
    }

    @Test fun liveChangesReorderTheLoadedWindowAndStopWhenHidden() = runTest(timeout = 5.seconds) {
        val directory = FakeConversationDirectory().apply {
            response = { ClientResult.Ready(ConversationPage(listOf(row(1, 10), row(2, 20)), null)) }
            pending = ClientResult.Ready(3)
        }
        val vm = model(directory)
        runCurrent()
        assertEquals(listOf("c000002", "c000001"), vm.state.value.rows.map { it.id })
        assertEquals(3, vm.state.value.requests)
        assertEquals(0, directory.watchers)
        vm.onAction(ConversationsAction.Visible(true))
        runCurrent()
        assertEquals(1, directory.watchers)
        directory.live.emit(ClientResult.Ready(ConversationChange.Upsert(row(1, 30).copy(title = "c000001"), titled = false)))
        runCurrent()
        assertEquals(listOf("c000001", "c000002"), vm.state.value.rows.map { it.id })
        assertEquals("Title 1", vm.state.value.rows.first().title)
        directory.live.emit(ClientResult.Ready(ConversationChange.Upsert(row(9, 40), titled = true)))
        directory.live.emit(ClientResult.Ready(ConversationChange.Remove("c000002")))
        runCurrent()
        assertEquals(listOf("c000009", "c000001"), vm.state.value.rows.map { it.id })
        directory.pending = ClientResult.Ready(0)
        directory.live.emit(ClientResult.Ready(ConversationChange.Requests))
        runCurrent()
        assertEquals(0, vm.state.value.requests)
        val pages = directory.requests.size
        directory.live.emit(ClientResult.Ready(ConversationChange.Reload))
        runCurrent()
        assertEquals(pages + 1, directory.requests.size)
        assertEquals(listOf("c000002", "c000001"), vm.state.value.rows.map { it.id })
        vm.onAction(ConversationsAction.Visible(false))
        runCurrent()
        assertEquals(0, directory.watchers)
    }

    @Test fun pageThatAddsNothingStopsAutomaticLoading() = runTest(timeout = 5.seconds) {
        // Activity falls with id, so pages after the first thousand rows are all older than the window.
        val directory = FakeConversationDirectory().apply {
            response = { cursor ->
                val start = cursor?.toInt() ?: 0
                ClientResult.Ready(ConversationPage((start until start + 100).map { row(it, 2_000L - it) }, (start + 100).toString()))
            }
        }
        val vm = model(directory)
        runCurrent()
        repeat(9) {
            vm.onAction(ConversationsAction.LoadMore)
            runCurrent()
            assertTrue(vm.state.value.autoLoad)
        }
        vm.onAction(ConversationsAction.LoadMore)
        runCurrent()
        assertEquals(ConversationWindow.CAPACITY, vm.state.value.rows.size)
        assertFalse(vm.state.value.autoLoad)
        assertTrue(vm.state.value.hasMore)
    }

    @Test fun fiftyThousandRowSourceStaysBoundedAndResponsive() = runTest(timeout = 60.seconds) {
        val total = 50_000
        val directory = FakeConversationDirectory().apply { response = synthetic(total) }
        val vm = model(directory)
        runCurrent()
        val first = vm.state.value.rows
        assertEquals(100, first.size)
        val activities = first.map { it.id.drop(1).toInt().toLong() * 7_919 % total }
        assertEquals(activities.sortedDescending(), activities)
        var pages = 1
        var slowest = kotlin.time.Duration.ZERO
        val elapsed = measureTime {
            while (vm.state.value.hasMore) {
                val step = measureTime {
                    vm.onAction(ConversationsAction.LoadMore)
                    runCurrent()
                }
                if (step > slowest) slowest = step
                pages++
                assertTrue(vm.state.value.rows.size <= ConversationWindow.CAPACITY)
            }
        }
        // After the full id-ordered scan the window holds the globally most recent rows.
        val ids = vm.state.value.rows.map { it.id.drop(1).toInt() }
        val kept = ids.map { it.toLong() * 7_919 % total }
        val newest = ((total - ConversationWindow.CAPACITY).toLong() until total.toLong()).toList()
        assertEquals(newest.sortedDescending(), kept)
        val timing = "total=${elapsed.inWholeMilliseconds}ms slowestPage=${slowest.inWholeMilliseconds}ms"
        println("SCALE conversations: rows=$total pages=$pages window=${vm.state.value.rows.size} $timing")
        directory.live.emit(ClientResult.Ready(ConversationChange.Upsert(row(1, total.toLong() + 1), titled = true)))
        vm.onAction(ConversationsAction.Visible(true))
        runCurrent()
        val upsert = measureTime {
            directory.live.emit(ClientResult.Ready(ConversationChange.Upsert(row(2, total.toLong() + 2), titled = true)))
            runCurrent()
        }
        assertEquals("c000002", vm.state.value.rows.first().id)
        println("SCALE conversations: live upsert into a full window ${upsert.inWholeMilliseconds}ms")
    }
}
