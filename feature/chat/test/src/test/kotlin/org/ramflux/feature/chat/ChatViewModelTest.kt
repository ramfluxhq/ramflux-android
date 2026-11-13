// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
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
class ChatViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: ChatViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
    private fun TestScope.load(vm: ChatViewModel) {
        vm.onAction(ChatAction.Load("a", "c"))
        runCurrent()
    }

    @Test fun failedReadIsNotAnEmptyConversationAndRefreshCanRecover() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            reading = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.problem)
        history.reading = { ClientResult.Ready(emptyList()) }
        vm.onAction(ChatAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.problem)
    }

    @Test fun deletedRowsRedactEvenAnOverdisclosingRepositoryAndDoNotLeakThroughToString() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            reading = { ClientResult.Ready(listOf(MessageSummary("m", "must-hide", false, true, "must-hide"))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals("", vm.state.value.rows.single().sender)
        assertEquals("Message deleted", vm.state.value.rows.single().content)
        assertFalse(vm.state.value.toString().contains("must-hide"))
    }

    @Test fun missingApprovedContentNeverBecomesCiphertextOrInventedMessageText() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            reading = { ClientResult.Ready(listOf(MessageSummary("m", "sender", false, false, null))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals("Message content is unavailable.", vm.state.value.rows.single().content)
    }

    @Test fun blockedSendKeepsDraftAndDoesNotEmitSuccessOrCreateMessageRow() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ChatAction.Draft("Private draft"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        assertEquals("Private draft", vm.state.value.draft)
        assertFalse(vm.state.value.canSend)
        assertNotNull(vm.state.value.unavailable)
        assertTrue(events.isEmpty())
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.toString().contains("Private draft"))
    }

    @Test fun accountChangeDuringReadDiscardsRowsAndDraft() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeChatAccounts()
        val history = FakeChatHistory().apply {
            reading = {
                barrier.await()
                ClientResult.Ready(listOf(MessageSummary("m", "sender", false, false, "Private")))
            }
        }
        val vm = ChatViewModel(history, accounts, FakeChatSession())
        observe(vm)
        runCurrent()
        vm.onAction(ChatAction.Load("a", "c"))
        runCurrent()
        vm.onAction(ChatAction.Draft("Draft"))
        accounts.active = "b"
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals("", vm.state.value.draft)
        assertNotNull(vm.state.value.problem)
    }

    @Test fun newerConversationAndSessionCloseFenceLateRows() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeChatSession()
        val history = FakeChatHistory().apply {
            reading = { conversation ->
                if (conversation == "old") withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(listOf(MessageSummary(conversation, "sender", false, false, conversation)))
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), session)
        observe(vm)
        runCurrent()
        vm.onAction(ChatAction.Load("a", "old"))
        runCurrent()
        vm.onAction(ChatAction.Load("a", "new"))
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertEquals(listOf("new"), vm.state.value.rows.map { it.id })
        vm.onAction(ChatAction.Draft("Draft"))
        session.background()
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals("", vm.state.value.draft)
    }

    @Test fun duplicateRefreshAndBackHaveOwnedWorkAndOneShotNavigation() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val history = FakeChatHistory().apply {
            reading = {
                barrier.await()
                ClientResult.Ready(emptyList())
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        vm.onAction(ChatAction.Load("a", "c"))
        runCurrent()
        vm.onAction(ChatAction.Refresh)
        runCurrent()
        assertEquals(1, history.reads)
        val events = mutableListOf<ChatEvent>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ChatAction.Back)
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertEquals(listOf(ChatEvent.Back), events)
        assertTrue(vm.state.value.rows.isEmpty())
        collection.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
    }
}
