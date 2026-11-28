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
import org.ramflux.designsystem.UiText
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

    @Test fun theRouteTitleShowsUntilTheHeaderNamesTheConversation() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        observe(vm)
        runCurrent()
        vm.onAction(ChatAction.Load("a", "c", title = "Row title"))
        runCurrent()
        assertEquals(UiText.Data("Row title"), vm.state.value.title)
        history.heading = ClientResult.Ready(ConversationHeader("Actual title"))
        vm.onAction(ChatAction.Refresh)
        runCurrent()
        assertEquals(UiText.Data("Actual title"), vm.state.value.title)
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

    @Test fun aResumeKeepsTheNamedTitleUntilTheReadNamesTheConversationAgain() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { heading = ClientResult.Ready(ConversationHeader("Actual title")) }
        val session = FakeChatSession()
        val vm = ChatViewModel(history, FakeChatAccounts(), session)
        observe(vm)
        runCurrent()
        load(vm)
        assertEquals(UiText.Data("Actual title"), vm.state.value.title)
        session.background()
        runCurrent()
        history.heading = ClientResult.Ready(ConversationHeader(null))
        session.status.value = ClientSnapshot(ClientPhase.OPEN, 2)
        runCurrent()
        assertEquals(UiText.Data("Actual title"), vm.state.value.title)
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

    @Test fun queuedSendsAreSealedAndResumedUnderTheirOperationIdAfterTheViewModelIsGone() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            sendResult = ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
        }
        val outbox = FakeChatOutbox()
        fun model(): ChatViewModel {
            val media = FakeChatMedia()
            val session = FakeChatSession()
            return ChatViewModel(
                history, FakeChatAccounts(), session, FakeChatDrafts(), media, FakeChatStager(),
                MediaTransfers(media, session, backgroundScope), FakeChatCards(), outbox
            )
        }
        val first = model()
        observe(first)
        runCurrent()
        load(first)
        first.onAction(ChatAction.Draft("survives"))
        first.onAction(ChatAction.Send)
        runCurrent()
        assertTrue(first.state.value.queued)
        val stored = outbox.stored["a/c"].orEmpty().single()
        assertEquals(history.sentOperations.single(), stored.operation)
        assertFalse(stored.toString().contains("survives"))
        // A new view model over the same store (process death or a lock) resumes the send once sending is available.
        // The offline refusal was definite, so the retry is a new operation, as in one session.
        history.sendResult = ClientResult.Ready(SendReceipt("sent"))
        val second = model()
        observe(second)
        runCurrent()
        load(second)
        runCurrent()
        assertEquals(2, history.sends)
        assertFalse(second.state.value.queued)
        assertTrue(outbox.stored["a/c"].isNullOrEmpty())
    }

    @Test fun aSendBlockedByAnIdentityChangeIsPersistedBlockedAndNeverResentByItself() = runTest(timeout = 5.seconds) {
        // The refusal is classified offline as well; the identity block still wins and the send is not queued.
        val blocked = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE, statusName = SendBlock.IDENTITY_CHANGE_PENDING)
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            sendResult = ClientResult.Failed(blocked)
        }
        val outbox = FakeChatOutbox()
        fun model(): ChatViewModel {
            val media = FakeChatMedia()
            val session = FakeChatSession()
            return ChatViewModel(
                history, FakeChatAccounts(), session, FakeChatDrafts(), media, FakeChatStager(),
                MediaTransfers(media, session, backgroundScope), FakeChatCards(), outbox
            )
        }
        val first = model()
        observe(first)
        runCurrent()
        load(first)
        first.onAction(ChatAction.Draft("held back"))
        first.onAction(ChatAction.Send)
        runCurrent()
        assertFalse("a blocked send is not queued", first.state.value.queued)
        assertEquals(LocalDelivery.FAILED, (first.state.value.rows.single() as MessageItem).delivery)
        val stored = outbox.stored["a/c"].orEmpty().single()
        assertTrue("the block is persisted", stored.blocked)
        // Reopen, unlock or relaunch: it comes back as a failed bubble and nothing is sent.
        history.sendResult = ClientResult.Ready(SendReceipt("sent"))
        val second = model()
        observe(second)
        runCurrent()
        load(second)
        runCurrent()
        assertEquals(1, history.sends)
        assertFalse(second.state.value.queued)
        assertEquals(LocalDelivery.FAILED, (second.state.value.rows.single() as MessageItem).delivery)
        assertTrue(outbox.stored["a/c"].orEmpty().single().blocked)
        // Only the person's explicit resend sends it, and it leaves the outbox when admitted.
        second.onAction(ChatAction.Retry(stored.id))
        runCurrent()
        assertEquals(2, history.sends)
        assertTrue(outbox.stored["a/c"].isNullOrEmpty())
    }

    @Test fun anAlreadyHandledAcceptNeverClearsANewerPendingChange() = runTest(timeout = 5.seconds) {
        class Identity : ContactIdentityRepository {
            val signals = MutableSharedFlow<IdentitySignal>(extraBufferCapacity = 4)
            val outcome = CompletableDeferred<AcceptOutcome>()
            var pending = IdentityChange("peer", null, "old", "a1", false, 0L)
            override suspend fun identity(account: String, contact: String) = ClientResult.Ready(IdentityState.CHANGED to pending)
            override suspend fun security(account: String, conversation: String): ClientResult<ConversationSecurity> =
                ClientResult.Unavailable(ClientFeature.CONTACTS, UnavailableReason.UNSUPPORTED_BY_CORE)
            override suspend fun accept(account: String, contact: String, newAnchor: String) = outcome.await()
            override fun changes(account: String): Flow<IdentitySignal> = signals
        }
        val identity = Identity()
        val history = FakeChatHistory().apply { heading = ClientResult.Ready(ConversationHeader(null, peer = "peer")) }
        val media = FakeChatMedia()
        val session = FakeChatSession()
        val vm = ChatViewModel(
            history, FakeChatAccounts(), session, FakeChatDrafts(), media, FakeChatStager(),
            MediaTransfers(media, session, backgroundScope), FakeChatCards(), FakeChatOutbox(), identity
        )
        observe(vm)
        runCurrent()
        load(vm)
        runCurrent()
        assertEquals(IdentityState.CHANGED, vm.state.value.identityState)
        vm.onAction(ChatAction.ReviewIdentity)
        runCurrent()
        vm.onAction(ChatAction.AcceptIdentity)
        runCurrent()
        // A newer change arrives while the accept runs; the core then answers that the first was handled already.
        identity.pending = IdentityChange("peer", null, "a1", "a2", false, 0L)
        identity.signals.emit(IdentitySignal.Changed(identity.pending))
        runCurrent()
        identity.outcome.complete(AcceptOutcome.AlreadyHandled)
        runCurrent()
        assertEquals("the newer change still blocks", IdentityState.CHANGED, vm.state.value.identityState)
        val sheet = vm.state.value.review!!
        assertEquals(IdentityReviewError.CHANGED_AGAIN, sheet.error)
        assertEquals("a2", sheet.newAnchor)
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

    @Test fun attachmentsDownloadOnTapOpenTheViewerAndErasureRevokesAndPurges() = runTest(timeout = 5.seconds) {
        val image = MediaAttachment("p", 0, MediaKind.IMAGE, "image/jpeg", "photo.jpg", 2048)
        val file = MediaAttachment("p", 1, MediaKind.FILE, "application/pdf", "report.pdf", 10)
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(MessageSummary("p", "me", true, false, "", 10, attachments = listOf(image, file)))) }
        }
        val media = FakeChatMedia()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, FakeChatStager())
        observe(vm)
        runCurrent()
        load(vm)
        val items = (vm.state.value.rows.single() as MessageItem).attachments
        assertEquals(listOf(AttachmentStatus.AVAILABLE, AttachmentStatus.AVAILABLE), items.map { it.status })
        assertTrue(media.downloads.isEmpty())
        vm.onAction(ChatAction.OpenAttachment("p/0"))
        runCurrent()
        assertEquals(listOf("p"), media.downloads)
        assertEquals(AttachmentStatus.READY, (vm.state.value.rows.single() as MessageItem).attachments[0].status)
        vm.onAction(ChatAction.OpenAttachment("p/0"))
        runCurrent()
        assertEquals("p/0", vm.state.value.viewer?.key)
        vm.onAction(ChatAction.CloseViewer)
        media.downloadResult = { TransferState.Expired }
        vm.onAction(ChatAction.OpenAttachment("p/1"))
        runCurrent()
        assertEquals(AttachmentStatus.EXPIRED, (vm.state.value.rows.single() as MessageItem).attachments[1].status)
        vm.onAction(ChatAction.Select("p"))
        vm.onAction(ChatAction.DeleteForEveryone)
        runCurrent()
        assertEquals(listOf("p"), media.revoked)
        assertTrue(media.purged.contains("p"))
    }

    @Test fun pickedImagesAreStagedAndSentThroughTheMediaRepository() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val media = FakeChatMedia().apply {
            sendResult = ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.REFUSED, statusName = "recipient_limit")
            )
        }
        val stager = FakeChatStager()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, stager)
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Picked(listOf("content://picked"), PickSource.CAMERA))
        runCurrent()
        assertEquals(1, media.sent.size)
        assertEquals(listOf("content://picked"), stager.captures)
        val row = vm.state.value.rows.single() as MessageItem
        assertEquals(LocalDelivery.FAILED, row.delivery)
        assertEquals(AttachmentStatus.UNSENT, row.attachments.single().status)
        media.sendResult = ClientResult.Ready(SendReceipt("media"))
        vm.onAction(ChatAction.Retry(row.id))
        runCurrent()
        assertEquals(2, media.sent.size)
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun cameraCapturesGoThroughTheCameraStagingPath() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val media = FakeChatMedia()
        val stager = FakeChatStager()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, stager)
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Picked(listOf("content://capture"), PickSource.CAMERA))
        runCurrent()
        assertEquals(listOf("content://capture"), stager.captures)
        assertEquals(1, media.sent.size)
    }

    @Test fun forwardingReEncryptsEveryItemForTheChosenConversation() = runTest(timeout = 5.seconds) {
        val image = MediaAttachment("p", 0, MediaKind.IMAGE, "image/jpeg", "photo.jpg", 2048)
        val file = MediaAttachment("p", 1, MediaKind.FILE, "application/pdf", "report.pdf", 10)
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = {
                ClientResult.Ready(listOf(MessageSummary("p", "peer", false, false, "caption", 10, attachments = listOf(image, file))))
            }
            targets = { cursor ->
                if (cursor == null) {
                    ClientResult.Ready(ForwardTargetPage(listOf(ForwardTarget("c", "Self"), ForwardTarget("d1", "Dana")), "next"))
                } else {
                    ClientResult.Ready(ForwardTargetPage(listOf(ForwardTarget("d2", "Eve")), null))
                }
            }
        }
        val media = FakeChatMedia().apply { held["p/0"] = java.io.File("/tmp/held-image") }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, FakeChatStager())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Select("p"))
        vm.onAction(ChatAction.Forward())
        runCurrent()
        val sheet = vm.state.value.forward!!
        assertNull(vm.state.value.selectedId)
        // The open conversation is never offered; the next directory page follows the cursor.
        assertEquals(listOf("d1"), sheet.rows.map { it.conversation })
        assertTrue(sheet.hasMore)
        vm.onAction(ChatAction.ForwardMore)
        runCurrent()
        assertEquals(listOf(null, "next"), history.targetCursors)
        assertEquals(listOf("d1", "d2"), vm.state.value.forward!!.rows.map { it.conversation })
        vm.onAction(ChatAction.ForwardTo("d2"))
        runCurrent()
        // The held item is copied, the missing one is imported first; both go out under a new operation.
        assertEquals(listOf("p"), media.downloads)
        assertEquals(listOf("/tmp/held-image", "/tmp/p"), media.copied)
        assertEquals("d2", media.destinations.single().conversation)
        assertEquals("caption", media.captions.single())
        assertEquals(2, media.sent.single().size)
        assertNull(vm.state.value.forward)
        assertEquals(uiText(R.string.chat_forward_sent), vm.state.value.deliveryNotice)
    }

    @Test fun aFailedForwardKeepsTheChooserOpenWithTheReason() = runTest(timeout = 5.seconds) {
        val image = MediaAttachment("p", 0, MediaKind.IMAGE, "image/jpeg", "photo.jpg", 2048)
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(MessageSummary("p", "peer", false, false, "", 10, attachments = listOf(image)))) }
            targets = { ClientResult.Ready(ForwardTargetPage(listOf(ForwardTarget("d1", "Dana")), null)) }
        }
        val media = FakeChatMedia().apply { downloadResult = { TransferState.Expired } }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, FakeChatStager())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Forward("p", 0))
        runCurrent()
        vm.onAction(ChatAction.ForwardTo("d1"))
        runCurrent()
        assertTrue(media.sent.isEmpty())
        assertNotNull(vm.state.value.forward?.problem)
        assertFalse(vm.state.value.forward!!.sending)
        vm.onAction(ChatAction.CloseForward)
        runCurrent()
        assertNull(vm.state.value.forward)
    }

    @Test fun pickedPhotosAndVideosOpenAPreviewThatReordersRemovesAndSendsOneAlbum() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val media = FakeChatMedia()
        val stager = FakeChatStager()
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, stager)
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Picked(listOf("content://a", "content://video-b", "content://c"), PickSource.PHOTO))
        runCurrent()
        val preview = vm.state.value.preview!!
        assertFalse(preview.preparing)
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.VIDEO, AttachmentKind.IMAGE), preview.items.map { it.kind })
        assertTrue(media.sent.isEmpty())
        vm.onAction(ChatAction.PreviewMove(2))
        vm.onAction(ChatAction.PreviewRemove(0))
        vm.onAction(ChatAction.PreviewCaption("trip"))
        runCurrent()
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.VIDEO), vm.state.value.preview!!.items.map { it.kind })
        vm.onAction(ChatAction.PreviewSend)
        runCurrent()
        assertNull(vm.state.value.preview)
        assertEquals(listOf("c", "video-b"), media.sent.single().map { it.fileName })
        assertEquals("trip", media.captions.single())
    }

    @Test fun refusedVideosExplainTheCapAndGroupLimitsHaveTheirOwnWords() = runTest(timeout = 5.seconds) {
        val history = FakeChatHistory().apply { capability = ClientResult.Ready(Unit) }
        val media = FakeChatMedia().apply {
            sendResult = ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.REFUSED, statusName = "recipient_limit")
            )
        }
        val stager = FakeChatStager().apply { visualResult = { StageResult.Refused(StageRefusal.VIDEO_TOO_LONG) } }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, stager)
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.Picked(listOf("content://video-long"), PickSource.PHOTO))
        runCurrent()
        assertNull(vm.state.value.preview)
        assertEquals(uiText(R.string.chat_media_video_too_long), vm.state.value.problem)
        vm.onAction(ChatAction.Picked(listOf("content://capture"), PickSource.CAMERA))
        runCurrent()
        assertEquals(uiText(R.string.chat_media_recipient_limit), vm.state.value.problem)
    }

    @Test fun theViewerStepsThroughTheReadyItemsOfAnAlbum() = runTest(timeout = 5.seconds) {
        val items = (0 until 3).map { MediaAttachment("p", it, MediaKind.IMAGE, "image/jpeg", "p$it.jpg", 10) }
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(MessageSummary("p", "peer", false, false, "", 10, attachments = items))) }
        }
        val media = FakeChatMedia().apply { items.forEach { held["p/" + it.index] = java.io.File("/tmp/p" + it.index) } }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), media, FakeChatStager())
        observe(vm)
        runCurrent()
        load(vm)
        vm.onAction(ChatAction.OpenAttachment("p/1"))
        runCurrent()
        assertEquals(ViewerPosition(2, 3), vm.state.value.viewerPosition)
        vm.onAction(ChatAction.ViewerStep(1))
        runCurrent()
        assertEquals("p/2", vm.state.value.viewer?.key)
        vm.onAction(ChatAction.ViewerStep(1))
        runCurrent()
        assertEquals("p/2", vm.state.value.viewer?.key)
        vm.onAction(ChatAction.CloseViewer)
        runCurrent()
        assertNull(vm.state.value.viewerPosition)
    }

    @Test fun mediaTimeUsesWesternDigitsLeftToRight() {
        assertEquals("0:07", mediaClock(7_400))
        assertEquals("10:00", mediaClock(600_000))
        assertEquals("1:02:03", mediaClock(3_723_000))
    }

    @Test fun theGalleryFiltersPagesClientSideFollowsTheCursorAndStaysBounded() = runTest(timeout = 60.seconds) {
        // 50,000 synthetic rows, every third with an image and every seventh with a file.
        val total = 50_000
        val history = FakeChatHistory().apply {
            capability = ClientResult.Ready(Unit)
            paging = { before ->
                val end = before?.toInt() ?: total
                val start = (end - 100).coerceAtLeast(0)
                val rows = (start until end).map { index ->
                    val media = buildList {
                        if (index % 3 == 0) add(MediaAttachment("m$index", 0, MediaKind.IMAGE, "image/jpeg", "i.jpg", 10))
                        if (index % 7 == 0) add(MediaAttachment("m$index", size, MediaKind.FILE, "application/pdf", "f.pdf", 10))
                    }
                    MessageSummary("m$index", "peer", false, false, "row $index", index.toLong(), attachments = media)
                }
                ClientResult.Ready(TimelinePage(rows, if (start > 0) start.toString() else null))
            }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession(), FakeChatDrafts(), FakeChatMedia(), FakeChatStager())
        observe(vm)
        runCurrent()
        load(vm)
        val reads = history.reads
        vm.onAction(ChatAction.OpenGallery)
        runCurrent()
        val first = vm.state.value.gallery!!
        assertFalse(first.loading)
        assertTrue(first.hasMore)
        assertEquals("m49998/0", first.items.first().key)
        assertTrue(first.items.all { it.kind == AttachmentKind.IMAGE })
        vm.onAction(ChatAction.GalleryTab(GalleryTab.FILES))
        runCurrent()
        assertTrue(vm.state.value.gallery!!.items.all { it.kind == AttachmentKind.FILE })
        val started = System.nanoTime()
        repeat(200) {
            vm.onAction(ChatAction.GalleryMore)
            runCurrent()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val gallery = vm.state.value.gallery!!
        val shown = gallery.items.size
        vm.onAction(ChatAction.GalleryTab(GalleryTab.MEDIA))
        runCurrent()
        // The window stops at 2,000 attachments; nothing beyond it is held in memory.
        assertEquals(2_000, shown + vm.state.value.gallery!!.items.size)
        assertFalse(vm.state.value.gallery!!.hasMore)
        assertTrue(history.reads - reads < 100)
        println("gallery over 50,000 rows: 2,000-item window filled in ${elapsedMs}ms, ${history.reads - reads} page reads")
        vm.onAction(ChatAction.CloseGallery)
        runCurrent()
        assertNull(vm.state.value.gallery)
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
