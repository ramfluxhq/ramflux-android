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
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
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

    @Test fun unverifiedGroupContextCannotReadHistoryOrEnableGroupPresentation() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        vm.onAction(ChatAction.Load("a", "canonical", "unverified-group"))
        runCurrent()
        assertEquals(0, history.reads)
        assertFalse(vm.state.value.loaded)
        assertNull(vm.state.value.groupId)
        assertNotNull(vm.state.value.problem)
    }

    @Test fun mixedRowsKeepAdjacentMessagesAndActualTitleTimeInState() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            heading = ClientResult.Ready(ConversationHeader("Actual title"))
            reading = {
                ClientResult.Ready(
                    listOf(
                        MessageSummary("one", "peer", false, false, "approved", 10),
                        CallSummary("call", 11),
                        MessageSummary("deleted", "must-hide", true, true, "must-hide", 12)
                    )
                )
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertTrue(vm.state.value.loaded)
        assertEquals(listOf("one", "call", "deleted"), vm.state.value.rows.map { it.id })
        assertTrue(vm.state.value.rows[1] is CallItem)
        assertEquals(org.ramflux.designsystem.UiText.Data("Actual title"), vm.state.value.title)
        assertEquals(12L, vm.state.value.rows.last().sentAt)
        assertEquals(uiText(R.string.ui_message_deleted), (vm.state.value.rows.last() as MessageItem).content)
        assertEquals(1, history.reads)
    }

    @Test fun titleFailureDoesNotDiscardAvailableMessages() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            heading = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
            reading = { ClientResult.Ready(listOf(MessageSummary("one", "peer", false, false, "approved"))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertTrue(vm.state.value.loaded)
        assertEquals(1, vm.state.value.rows.size)
        assertNull(vm.state.value.title)
        assertNotNull(vm.state.value.headerProblem)
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
        assertEquals("", (vm.state.value.rows.single() as MessageItem).sender)
        assertEquals(uiText(R.string.ui_message_deleted), (vm.state.value.rows.single() as MessageItem).content)
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
        assertEquals(uiText(R.string.ui_message_content_is_unavailable), (vm.state.value.rows.single() as MessageItem).content)
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

    @Test fun unavailableAccountAndFailedSendCapabilityStayVisibleWithoutFabricatedSuccess() = runTest(timeout = 5.seconds) {
        val accounts = FakeChatAccounts().apply {
            failure = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
        }
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, accounts, FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertNotNull(vm.state.value.unavailable)
        assertFalse(vm.state.value.loaded)
        assertEquals(0, history.reads)
        accounts.failure = null
        history.capability = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        vm.onAction(ChatAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertFalse(vm.state.value.canSend)
        assertNotNull(vm.state.value.problem)
    }

    @Test fun submittedSendClearsDraftAndDoesNotLeakIt() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        val events = mutableListOf<ChatEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ChatAction.Draft("Private draft"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        assertEquals("", vm.state.value.draft)
        assertEquals(listOf(ChatEvent.Sent), events)
        assertEquals(1, history.sends)
        assertFalse(history.sentOperation.isNullOrEmpty())
        assertFalse(vm.state.value.toString().contains("Private draft"))
    }

    @Test fun failedSendBecomesAFailedBubbleThatCanBeRetriedOrDiscarded() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            sendResult = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.NONE))
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("Private draft"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        val failed = vm.state.value.rows.single() as MessageItem
        assertEquals(LocalDelivery.FAILED, failed.delivery)
        assertEquals("", vm.state.value.draft)
        assertNotNull(vm.state.value.problem)
        assertEquals(1, history.sends)
        // A definite failure is retried as a new operation.
        vm.onAction(ChatAction.Retry(failed.id))
        runCurrent()
        assertEquals(2, history.sends)
        assertNotEquals(history.sentOperations[0], history.sentOperations[1])
        vm.onAction(ChatAction.Discard(failed.id))
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.toString().contains("Private draft"))
    }

    @Test fun offlineSendsWaitInTheQueueAndGoOutAfterTheNextRead() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            sendResult = ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("later"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        assertTrue(vm.state.value.queued)
        assertEquals(LocalDelivery.QUEUED, (vm.state.value.rows.single() as MessageItem).delivery)
        assertNull(vm.state.value.problem)
        history.sendResult = ClientResult.Ready(SendReceipt("sent"))
        vm.onAction(ChatAction.Refresh)
        runCurrent()
        assertEquals(2, history.sends)
        assertFalse(vm.state.value.queued)
        assertTrue(vm.state.value.rows.none { (it as MessageItem).delivery != null })
    }

    @Test fun unknownSendRetriesTheSameOperationAndALaterEqualTextIsNew() = runTest(timeout = 5.seconds) {
        val unknown = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            sendResult = unknown
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("same"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        val first = history.sentOperations.single()
        val local = vm.state.value.rows.single().id
        vm.onAction(ChatAction.Retry(local))
        runCurrent()
        assertEquals(listOf(first, first), history.sentOperations)
        history.sendResult = ClientResult.Ready(SendReceipt("sent"))
        vm.onAction(ChatAction.Retry(local))
        runCurrent()
        assertEquals(listOf(first, first, first), history.sentOperations)
        assertTrue(vm.state.value.rows.isEmpty())
        vm.onAction(ChatAction.Draft("same"))
        vm.onAction(ChatAction.Send)
        runCurrent()
        assertEquals(4, history.sentOperations.size)
        assertNotEquals(first, history.sentOperations.last())
    }

    @Test fun olderPagesPrependWithoutDuplicatesAndTheWindowStaysBounded() = runTest(timeout = 20.seconds) {
        val total = 50_000
        val history = FakeChatHistory().apply { paging = syntheticPages(total) }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(100, vm.state.value.rows.size)
        assertEquals("m${total - 1}", vm.state.value.rows.last().id)
        assertTrue(vm.state.value.hasOlder)
        val started = System.nanoTime()
        repeat(30) {
            vm.onAction(ChatAction.LoadOlder)
            runCurrent()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val ids = vm.state.value.rows.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue("window ${ids.size}", ids.size <= 1_000)
        assertTrue(vm.state.value.hasOlder)
        // Thirty pages over a 50,000-row source stay well inside the test budget on the JVM.
        assertTrue("paging took $elapsedMs ms", elapsedMs < 10_000)
        println("CHAT_SCALE: 30 older pages over $total rows in $elapsedMs ms, window ${ids.size}")
    }

    @Test fun shownIncomingMessagesAreMarkedReadOnceAndTheDividerFollowsTheUnreadCount() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            heading = ClientResult.Ready(ConversationHeader("Peer", unread = 2))
            reading = {
                ClientResult.Ready(
                    listOf(
                        MessageSummary("a", "peer", false, false, "one", 10),
                        MessageSummary("b", "peer", false, false, "two", 11),
                        MessageSummary("c", "me", true, false, "mine", 12),
                        MessageSummary("d", "peer", false, false, "three", 13)
                    )
                )
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals("b", vm.state.value.firstUnreadId)
        vm.onAction(ChatAction.Shown("c"))
        runCurrent()
        assertEquals(listOf("b"), history.marked)
        vm.onAction(ChatAction.Shown("b"))
        runCurrent()
        assertEquals(listOf("b"), history.marked)
        vm.onAction(ChatAction.Shown("d"))
        runCurrent()
        assertEquals(listOf("b", "d"), history.marked)
    }

    @Test fun draftsAreSealedOnPauseRestoredOnLoadAndErasedOnSend() = runTest(timeout = 5.seconds) {
        val drafts = FakeChatDrafts()
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), drafts)
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("half written"))
        vm.onAction(ChatAction.Pause)
        runCurrent()
        assertEquals("half written", drafts.stored["a/c"])
        val restored = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), drafts)
        observe(restored)
        runCurrent()
        load(restored)
        assertEquals("half written", restored.state.value.draft)
        restored.onAction(ChatAction.Send)
        runCurrent()
        assertNull(drafts.stored["a/c"])
    }

    @Test fun reactionsSendTheChosenGraphemeAndRefuseOthers() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(MessageSummary("m", "peer", false, false, "hi", 10))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Select("m"))
        vm.onAction(ChatAction.React("ab"))
        runCurrent()
        assertTrue(history.reactions.isEmpty())
        vm.onAction(ChatAction.Select("m"))
        vm.onAction(ChatAction.React("\u2764\uFE0F"))
        runCurrent()
        assertEquals(listOf("\u2764\uFE0F"), history.reactions)
    }

    private fun syntheticPages(total: Int): suspend (String?) -> ClientResult<TimelinePage> = { before ->
        val end = before?.toInt() ?: total
        val start = (end - 100).coerceAtLeast(0)
        val rows = (start until end).map { MessageSummary("m$it", "peer", it % 3 == 0, false, "row $it", it.toLong()) }
        ClientResult.Ready(TimelinePage(rows, if (start > 0) start.toString() else null))
    }

    @Test fun editPassesTheObservedRevision() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(MessageSummary("m", "me", true, false, "approved", 10, 4))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Select("m"))
        vm.onAction(ChatAction.Edit)
        vm.onAction(ChatAction.Send)
        runCurrent()
        assertEquals(4L, history.editedRevision)
        assertEquals("", vm.state.value.draft)
    }

    @Test fun incomingRefreshDoesNotRequireTypingAndOtherConversationsStayQuiet() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(1, history.reads)
        assertFalse(vm.state.value.peerTyping)
        history.signal(ChatSignal(refresh = true))
        runCurrent()
        assertEquals(2, history.reads)
        assertFalse(vm.state.value.peerTyping)
        val until = System.currentTimeMillis() + 60_000
        history.signal(ChatSignal(refresh = false, peerTyping = history.peer("other", true, until)))
        runCurrent()
        assertFalse(vm.state.value.peerTyping)
        history.signal(ChatSignal(refresh = false, peerTyping = history.peer("c", true, until)))
        runCurrent()
        assertTrue(vm.state.value.peerTyping)
        history.signal(ChatSignal(refresh = false, peerTyping = history.peer("c", false, until)))
        runCurrent()
        assertFalse(vm.state.value.peerTyping)
    }

    @Test fun lostTypingStopClearsWhenTheIndicatorDeadlinePasses() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        val until = System.currentTimeMillis() + 60_000
        history.signal(ChatSignal(refresh = false, peerTyping = history.peer("c", true, until)))
        runCurrent()
        assertTrue(vm.state.value.peerTyping)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(vm.state.value.peerTyping)
    }

    @Test fun authorizationDoesNotHideTypingOrStopTheNextAttempt() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            typingResult = ClientResult.Failed(
                ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED)
            )
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("hello"))
        runCurrent()
        assertNull(vm.state.value.typingProblem)
        assertEquals(1, history.typingCalls)
        vm.onAction(ChatAction.Draft("hello again"))
        runCurrent()
        assertNull(vm.state.value.typingProblem)
        assertEquals(2, history.typingCalls)
    }

    @Test fun unavailableTypingUsesCatalogCopyAndDoesNotRepeat() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Draft("hello"))
        runCurrent()
        assertEquals(uiText(R.string.ui_typing_unavailable), vm.state.value.typingProblem)
        assertEquals(1, history.typingCalls)
        vm.onAction(ChatAction.Draft("hello again"))
        runCurrent()
        assertEquals(1, history.typingCalls)
    }

    @Test fun oneLockedAccountClosesPrivateDataInsteadOfSayingTheAccountChanged() = runTest(timeout = 5.seconds) {
        val accounts = FakeChatAccounts().apply {
            unlocked = false
            followActive = false
        }
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, accounts, FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(uiText(R.string.ui_private_data_is_closed), vm.state.value.problem)
        assertNotEquals(
            uiText(R.string.ui_the_active_account_changed_return_to_conversations),
            vm.state.value.problem
        )
        assertEquals(0, history.reads)
    }

    @Test fun aDifferentUnlockedAccountStillSaysTheAccountChanged() = runTest(timeout = 5.seconds) {
        val accounts = FakeChatAccounts().apply { active = "other" }
        val vm = ChatViewModel(FakeChatHistory(), accounts, FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(
            uiText(R.string.ui_the_active_account_changed_return_to_conversations),
            vm.state.value.problem
        )
    }

    @Test fun actionsCloseTheSelectionAndReceiptsAreTyped() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = {
                ClientResult.Ready(
                    listOf(
                        MessageSummary("m", "peer", false, false, "hi", 10),
                        MessageSummary("o", "me", true, false, "mine", 11, receipts = listOf(MessageReceipt("peer", "delivered")))
                    )
                )
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(Receipt.DELIVERED, (vm.state.value.rows.last() as MessageItem).receipt)
        assertEquals(11L, vm.state.value.rows.last().sentAt)
        vm.onAction(ChatAction.Select("m"))
        runCurrent()
        assertEquals("m", vm.state.value.selectedId)
        vm.onAction(ChatAction.Dismiss)
        runCurrent()
        assertNull(vm.state.value.selectedId)
        vm.onAction(ChatAction.Select("m"))
        runCurrent()
        vm.onAction(ChatAction.Reply)
        runCurrent()
        assertNull(vm.state.value.selectedId)
        assertEquals(ComposerMode.REPLY, vm.state.value.composer)
        assertEquals("m", vm.state.value.actionTarget)
    }
}
