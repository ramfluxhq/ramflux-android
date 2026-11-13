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
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary(id, "Title", ULong.MAX_VALUE, false, true)), next))
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
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("one", "Title", 1u, false, true)), "same"))
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
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("private", "Old account", 1u, false, true)), null))
            }
        }
        val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
        observe(vm)
        runCurrent()
        accounts.catalog = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("b", "Other", true, "unlocked")), "b"))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.accountRequired)
        assertNotNull(vm.state.value.problem)
    }

    @Test fun sessionCloseDiscardsLateDirectoryEvenWhenRepositoryIgnoresCancellation() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeConversationSession()
        val directory = FakeConversationDirectory().apply {
            response = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(ConversationPage(listOf(ConversationSummary("private", "Private", 1u, false, true)), null))
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
            response = { ClientResult.Ready(ConversationPage(listOf(ConversationSummary("one", "Title", 1u, false, true)), null)) }
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

    @Test fun manualUnreadActivityAndAccountLabelComeFromAuthoritativeFacts() = runTest(timeout = 5.seconds) {
        val directory = FakeConversationDirectory().apply {
            response = {
                val row = ConversationSummary("manual", "Team", 0u, false, true, 1700000000)
                ClientResult.Ready(ConversationPage(listOf(row), null))
            }
        }
        val vm = ConversationsViewModel(directory, FakeConversationAccounts(), FakeConversationSession())
        observe(vm)
        runCurrent()
        assertEquals("Work", vm.state.value.accountLabel)
        assertTrue(vm.state.value.rows.single().hasUnread)
        assertEquals("0", vm.state.value.rows.single().unread)
        assertTrue(vm.state.value.rows.single().timestamp.isNotBlank())
    }

    @Test fun failedOrUnavailableRevalidationNeverClaimsMissingAccountOrKeepsRows() = runTest(timeout = 5.seconds) {
        for (failure in listOf(failed(), ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED))) {
            val accounts = FakeConversationAccounts().apply { listing = { if (reads == 1) catalog else failure } }
            val directory = FakeConversationDirectory().apply {
                response = { ClientResult.Ready(ConversationPage(listOf(ConversationSummary("c", "Private", 0u, false, true)), null)) }
            }
            val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
            observe(vm)
            runCurrent()
            assertFalse(vm.state.value.accountRequired)
            assertFalse(vm.state.value.loaded)
            assertTrue(vm.state.value.rows.isEmpty())
            assertTrue(vm.state.value.problem != null || vm.state.value.unavailable != null)
            if (failure is ClientResult.Unavailable) assertEquals(failure.reason, vm.state.value.unavailableReason)
        }
    }

    @Test fun unavailableInitialAccountAndSearchFailureHaveVisibleTypedReasons() = runTest(timeout = 5.seconds) {
        val accounts = FakeConversationAccounts().apply {
            catalog = ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED)
        }
        val directory = FakeConversationDirectory()
        val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.accountRequired)
        assertNotNull(vm.state.value.unavailable)
        assertTrue(directory.requests.isEmpty())
        accounts.catalog = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a"))
        directory.search = failed()
        vm.onAction(ConversationsAction.Refresh)
        runCurrent()
        assertNotNull(vm.state.value.problem)
        assertEquals(ClientFailureKind.STORAGE, vm.state.value.problemKind)
        assertFalse(vm.state.value.searchAvailable)
        assertFalse(vm.state.value.accountRequired)
    }

    @Test fun failedOrUnavailableOpenRevalidationClearsRowsWithoutNavigation() = runTest(timeout = 5.seconds) {
        for (failure in listOf(failed(), ClientResult.Unavailable(ClientFeature.ONBOARDING, UnavailableReason.CUSTODY_PROVIDER_REQUIRED))) {
            val accounts = FakeConversationAccounts()
            val directory = FakeConversationDirectory().apply {
                canOpen = ClientResult.Ready(Unit)
                response = { ClientResult.Ready(ConversationPage(listOf(ConversationSummary("c", "Private", 0u, false, true)), null)) }
            }
            val vm = ConversationsViewModel(directory, accounts, FakeConversationSession())
            observe(vm)
            runCurrent()
            val events = mutableListOf<ConversationsEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
            accounts.catalog = failure
            vm.onAction(ConversationsAction.Open("c"))
            runCurrent()
            assertTrue(events.isEmpty())
            assertTrue(vm.state.value.rows.isEmpty())
            assertFalse(vm.state.value.accountRequired)
            assertTrue(vm.state.value.problem != null || vm.state.value.unavailable != null)
        }
    }
}
