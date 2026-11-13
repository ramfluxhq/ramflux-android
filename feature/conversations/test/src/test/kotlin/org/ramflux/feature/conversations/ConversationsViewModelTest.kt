// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
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

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: ConversationsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
    private fun failed() = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))

    @Test fun missingActiveAccountNeverFallsBackToFirstAccountOrReadsItsDirectory() = runTest(timeout = 5.seconds) {
        val accounts = FakeConversationAccounts().apply {
            catalog = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), null))
        }
        val directory = FakeConversationDirectory()
        val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
        observe(vm)
        runCurrent()
        assertTrue(vm.state.value.accountRequired)
        assertTrue(directory.requests.isEmpty())
        assertFalse(vm.state.value.loaded)
    }

    @Test fun failedReadIsDifferentFromSuccessfulEmptyAndSearchIsTypedUnavailable() = runTest(timeout = 5.seconds) {
        val directory = FakeConversationDirectory().apply { response = { failed() } }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.problem)
        directory.response = { ClientResult.Ready(ConversationPage(emptyList(), null)) }
        vm.onAction(ConversationsAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.problem)
        assertNotNull(vm.state.value.unavailable)
        assertFalse(vm.state.value.searchAvailable)
    }

    @Test fun paginationKeepsAccountAndSdkUnreadCountsWithoutRecomputation() = runTest(timeout = 5.seconds) {
        val directory = FakeConversationDirectory().apply {
            response = { cursor ->
                val id = if (cursor == null) "one" else "two"
                val next = if (cursor == null) "opaque" else null
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary(id, "Title", ULong.MAX_VALUE, false)), next))
            }
        }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession())
        observe(vm)
        runCurrent()
        vm.onAction(ConversationsAction.LoadMore)
        runCurrent()
        assertEquals(listOf("one", "two"), vm.state.value.rows.map { it.id })
        assertEquals(ULong.MAX_VALUE.toString(), vm.state.value.rows.first().unread)
        assertEquals(listOf("a" to null, "a" to "opaque"), directory.requests)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun duplicateRefreshSharesOneRequestAndDuplicatePageCannotCorruptRows() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val directory = FakeConversationDirectory().apply {
            response = {
                barrier.await()
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("one", "Title", 1u, false)), "same"))
            }
        }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession())
        observe(vm)
        runCurrent()
        vm.onAction(ConversationsAction.Refresh)
        vm.onAction(ConversationsAction.Refresh)
        runCurrent()
        assertEquals(1, directory.requests.size)
        barrier.complete(Unit)
        runCurrent()
        vm.onAction(ConversationsAction.LoadMore)
        runCurrent()
        assertEquals(1, vm.state.value.rows.size)
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.hasMore)
    }

    @Test fun accountSwitchDuringReadDiscardsOldAccountRows() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeConversationAccounts()
        val directory = FakeConversationDirectory().apply {
            response = {
                barrier.await()
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("private", "Old account", 1u, false)), null))
            }
        }
        val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
        observe(vm)
        runCurrent()
        accounts.catalog = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("b", "Other", true, "unlocked")), "b"))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertTrue(vm.state.value.accountRequired)
    }

    @Test fun sessionCloseDiscardsLateDirectoryEvenWhenRepositoryIgnoresCancellation() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeConversationSession()
        val directory = FakeConversationDirectory().apply {
            response = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("private", "Private", 1u, false)), null))
            }
        }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), session)
        observe(vm)
        runCurrent()
        session.background()
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.problem)
    }

    @Test fun unavailableChatDoesNotNavigateAndInstalledChatUsesOneShotEvent() = runTest(timeout = 5.seconds) {
        val directory = FakeConversationDirectory().apply {
            response = { ClientResult.Ready(ConversationPage(listOf(ConversationSummary("one", "Title", 1u, false)), null)) }
        }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession())
        observe(vm)
        runCurrent()
        val events = mutableListOf<ConversationsEvent>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ConversationsAction.Open("one"))
        runCurrent()
        assertTrue(events.isEmpty())
        assertNotNull(vm.state.value.unavailable)
        directory.canOpen = ClientResult.Ready(Unit)
        vm.onAction(ConversationsAction.Open("one"))
        runCurrent()
        assertEquals(listOf(ConversationsEvent.OpenChat("a", "one")), events)
        collection.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
    }
}
