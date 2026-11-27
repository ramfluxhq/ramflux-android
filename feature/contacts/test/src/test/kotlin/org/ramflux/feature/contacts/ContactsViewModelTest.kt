// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.contacts
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
import org.ramflux.designsystem.*
import org.ramflux.feature.contacts.impl.R
@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: ContactsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    private fun TestScope.requestVm(
        repo: FakeContactDirectory,
        accounts: FakeContactsAccounts = FakeContactsAccounts(),
        session: FakeContactsSession = FakeContactsSession()
    ): ContactsViewModel {
        repo.capability = ClientResult.Ready(Unit)
        repo.requestReading = { ClientResult.Ready(listOf(inbound())) }
        val vm = ContactsViewModel(repo, accounts, session)
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Tab(ContactsTab.STRANGERS))
        runCurrent()
        vm.onAction(ContactsAction.Select("request"))
        return vm
    }

    @Test fun safetyPageReadsActualMaterialButDoesNotVerifyAnUnboundComparison() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(ContactSummary("link", "peer", "Name", false, "root"))) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Select("link"))
        vm.onAction(ContactsAction.Page(ContactsPage.SAFETY))
        runCurrent()
        assertEquals(
            SafetyItem("1234 5678", "abcdef", uiText(R.string.contacts_safety_state_unverified), verified = false, verifiable = false),
            vm.state.value.safety
        )
        vm.onAction(ContactsAction.Operate(ContactOperation.VERIFY))
        runCurrent()
        assertNull(vm.state.value.approval)
        assertTrue(repo.submitted.isEmpty())
    }

    @Test fun numbersMatchVerifiesTheComparedRootAfterConfirmationAndRereads() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(ContactSummary("link", "peer", "Name", false, "root"))) }
            safetyResult = ClientResult.Ready(ContactSafety("1234 5678", "abcdef", SafetyState.UNVERIFIED, verifiable = true))
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Select("link"))
        vm.onAction(ContactsAction.Page(ContactsPage.SAFETY))
        runCurrent()
        vm.onAction(ContactsAction.Operate(ContactOperation.VERIFY))
        runCurrent()
        assertEquals(ContactOperation.VERIFY, vm.state.value.approval?.kind)
        assertTrue(repo.submitted.isEmpty())
        repo.safetyResult = ClientResult.Ready(ContactSafety("1234 5678", "abcdef", SafetyState.VERIFIED, verifiable = false))
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        val command = repo.submitted.single()
        assertEquals(ContactOperation.VERIFY, command.operation)
        assertEquals("root", command.target)
        assertEquals(true, vm.state.value.safety?.verified)
        assertEquals(true, vm.state.value.selected?.verified)
        assertEquals(uiText(R.string.contacts_verified), vm.state.value.selected?.status)
        assertEquals(uiText(R.string.contacts_verified), vm.state.value.rows.single { it.id == "link" }.status)
        assertEquals(null, vm.state.value.operationNotice)
        assertEquals(ContactsPage.SAFETY, vm.state.value.page)
    }

    @Test fun removeBlockAndUnblockActOnTheLinkAfterConfirmationAndReturnToTheList() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(ContactSummary("link", "peer", "Name", false, "root"))) }
            blockedReading = { ClientResult.Ready(listOf(ContactSummary("gone", "other", "Other", false, "root2"))) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Select("link"))
        vm.onAction(ContactsAction.Operate(ContactOperation.UNBLOCK))
        runCurrent()
        assertNull(vm.state.value.approval)
        vm.onAction(ContactsAction.Operate(ContactOperation.REMOVE))
        runCurrent()
        assertEquals(listOf("Name"), vm.state.value.approval?.targets)
        assertEquals(uiText(R.string.contacts_remove_explanation), vm.state.value.approval?.explanation)
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        assertEquals(
            ContactCommand("a", repo.submitted.single().operationId, ContactOperation.REMOVE, "link", "peer"),
            repo.submitted.single()
        )
        assertEquals(ContactsPage.LIST, vm.state.value.page)
        vm.onAction(ContactsAction.Tab(ContactsTab.BLOCKED))
        runCurrent()
        vm.onAction(ContactsAction.Select("gone"))
        vm.onAction(ContactsAction.Operate(ContactOperation.REMOVE))
        runCurrent()
        assertNull(vm.state.value.approval)
        vm.onAction(ContactsAction.Operate(ContactOperation.UNBLOCK))
        runCurrent()
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        assertEquals(ContactOperation.UNBLOCK to "gone", repo.submitted.last().let { it.operation to it.target })
    }

    @Test fun fiftyThousandRowSourceStaysWindowedAndLiveChangesReloadWhileVisible() = runTest(timeout = 20.seconds) {
        val rows = List(50_000) { ContactSummary("link-$it", "peer-$it", "Name $it", it % 3 == 0, "root-$it") }
        val repo = FakeContactDirectory().apply { reading = { ClientResult.Ready(rows) } }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        val started = System.nanoTime()
        var slowest = 0L
        repeat(499) {
            val before = System.nanoTime()
            vm.onAction(ContactsAction.LoadMore)
            runCurrent()
            slowest = maxOf(slowest, System.nanoTime() - before)
            assertTrue(vm.state.value.rows.size <= 600)
        }
        val total = (System.nanoTime() - started) / 1_000_000
        println("contacts 50k: 500 pages in $total ms, slowest page ${slowest / 1_000_000} ms, window ${vm.state.value.rows.size}")
        assertEquals("link-49999", vm.state.value.rows.last().id)
        assertFalse(vm.state.value.hasMore)
        assertTrue(vm.state.value.hasBefore)
        vm.onAction(ContactsAction.LoadPrevious)
        runCurrent()
        assertEquals("link-49300", vm.state.value.rows.first().id)
        assertTrue(vm.state.value.rows.size <= 600)
        assertEquals(0, repo.watchers)
        vm.onAction(ContactsAction.Visible(true))
        runCurrent()
        assertEquals(1, repo.watchers)
        val reads = repo.reads
        repo.live.emit(ClientResult.Ready(ContactChange.CONTACTS))
        runCurrent()
        assertEquals(reads + 1, repo.reads)
        assertEquals(null, repo.cursors.last())
        repo.live.emit(ClientResult.Ready(ContactChange.SAFETY))
        runCurrent()
        vm.onAction(ContactsAction.Visible(false))
        runCurrent()
        assertEquals(0, repo.watchers)
    }

    @Test fun hostDenialNeverSubmitsAndSingleConfirmationBindsExactTarget() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory()
        val vm = requestVm(repo)
        vm.onAction(ContactsAction.Operate(ContactOperation.ACCEPT))
        runCurrent()
        assertEquals(listOf("Name"), vm.state.value.approval?.targets)
        vm.onAction(ContactsAction.Deny)
        runCurrent()
        assertTrue(repo.submitted.isEmpty())
        vm.onAction(ContactsAction.Operate(ContactOperation.ACCEPT))
        runCurrent()
        assertTrue(repo.submitted.isEmpty())
        runCurrent()
        assertTrue(repo.submitted.isEmpty())
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        val command = repo.submitted.single()
        assertEquals("a", command.account)
        assertEquals("request", command.target)
        assertEquals("peer", command.principal)
        assertFalse(command.operationId.isBlank())
        assertNotNull(vm.state.value.operationNotice)
        assertEquals(listOf("request"), vm.state.value.rows.map { it.id })
    }

    @Test fun unknownOutcomeDoesNotRemoveRequestOrGrantConversationAndShowsOriginalOperationIdentity() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            submitting = { ClientResult.Failed(ClientProblem(ClientFailureKind.SHUTDOWN_PENDING, 10, ClientOutcome.UNKNOWN)) }
        }
        val vm = requestVm(repo)
        vm.onAction(ContactsAction.Operate(ContactOperation.DECLINE))
        runCurrent()
        runCurrent()
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        assertEquals(listOf("request"), vm.state.value.rows.map { it.id })
        assertNull(vm.state.value.operationNotice)
        assertEquals(uiText(R.string.contacts_unknown_operation, UiText.Data(repo.submitted.single().operationId)), vm.state.value.problem)
    }

    @Test fun accountChangeOrSessionCloseInvalidatesPreparedApprovalBeforeAnyNativeSubmission() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory()
        val accounts = FakeContactsAccounts()
        val session = FakeContactsSession()
        val vm = requestVm(repo, accounts, session)
        vm.onAction(ContactsAction.Operate(ContactOperation.BLOCK))
        runCurrent()
        runCurrent()
        accounts.result = ClientResult.Ready(AccountCatalog(emptyList(), null))
        vm.onAction(ContactsAction.Confirm)
        runCurrent()
        assertTrue(repo.submitted.isEmpty())
        assertNull(vm.state.value.approval)
        session.background()
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun filteringIsDebouncedAndReadThroughTheWindowedSource() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply { reading = { ClientResult.Ready(listOf(ContactSummary("one", "peer", "Name", false))) } }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        val reads = repo.reads
        vm.onAction(ContactsAction.Query("mis"))
        vm.onAction(ContactsAction.Query("missing"))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(reads, repo.reads)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(reads + 1, repo.reads)
        vm.onAction(ContactsAction.Query(""))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("one"), vm.state.value.rows.map { it.id })
    }

    @Test fun failedReadIsDistinctFromEmptyAndRefreshRecovers() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            reading = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.problem)
        repo.reading = { ClientResult.Ready(emptyList()) }
        vm.onAction(ContactsAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.problem)
    }

    @Test fun requestsKeepDirectionExactCountExpiryAndNoAutomaticContactOrChat() = runTest(timeout = 5.seconds) {
        val request = inbound()
        val repo = FakeContactDirectory().apply {
            requestReading = {
                ClientResult.Ready(
                    listOf(
                        request,
                        request.copy(id = "sent", direction = RequestDirection.OUTBOUND, status = ContactRequestStatus.ACCEPTED)
                    )
                )
            }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Tab(ContactsTab.STRANGERS))
        runCurrent()
        assertEquals(listOf("request"), vm.state.value.rows.map { it.id })
        assertEquals(UiText.Quantity(R.plurals.contacts_messages, ULong.MAX_VALUE), vm.state.value.rows.single().count)
        assertEquals(UiText.DateTime(1700000000), vm.state.value.rows.single().expires)
        vm.onAction(ContactsAction.Tab(ContactsTab.SENT))
        runCurrent()
        assertEquals(listOf("sent"), vm.state.value.rows.map { it.id })
        assertEquals(uiText(R.string.contacts_accepted), vm.state.value.rows.single().status)
    }

    @Test fun unavailableActionsDoNotRemoveRequestsOrInventSuccessAndKeepAddDraft() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply { requestReading = { ClientResult.Ready(listOf(inbound())) } }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Tab(ContactsTab.STRANGERS))
        runCurrent()
        vm.onAction(ContactsAction.Select("request"))
        for (operation in listOf(
            ContactOperation.ACCEPT,
            ContactOperation.DECLINE,
            ContactOperation.BLOCK,
            ContactOperation.REPORT,
            ContactOperation.REPLY
        )) {
            vm.onAction(ContactsAction.Operate(operation))
            runCurrent()
            assertNotNull(vm.state.value.unavailable)
            assertEquals(1, vm.state.value.rows.size)
        }
        vm.onAction(ContactsAction.Page(ContactsPage.ADD))
        vm.onAction(ContactsAction.Address("Peer draft"))
        vm.onAction(ContactsAction.Operate(ContactOperation.ADD))
        runCurrent()
        assertEquals("Peer draft", vm.state.value.address)
        assertFalse(vm.state.value.toString().contains("Peer draft"))
    }

    @Test fun accountChangeDuringReadDiscardsPrivateRowsAndNeverSelectsFirstAccount() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeContactsAccounts()
        val repo = FakeContactDirectory().apply {
            reading = {
                barrier.await()
                ClientResult.Ready(listOf(ContactSummary("private", "peer", "Name", false)))
            }
        }
        val vm = ContactsViewModel(repo, accounts, FakeContactsSession())
        observe(vm)
        runCurrent()
        accounts.result = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("other", "Other", true, "unlocked")), "other"))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNotNull(vm.state.value.problem)
        assertNull(vm.state.value.accountLabel)
    }

    @Test fun failedAccountReadIsNotMissingAccountAndStopsDirectoryReads() = runTest(timeout = 5.seconds) {
        val accounts = FakeContactsAccounts().apply {
            result = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
        val repo = FakeContactDirectory()
        val vm = ContactsViewModel(repo, accounts, FakeContactsSession())
        observe(vm)
        runCurrent()
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.accountRequired)
        assertEquals(0, repo.reads)
        accounts.result = ClientResult.Ready(AccountCatalog(emptyList(), null))
        vm.onAction(ContactsAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.accountRequired)
        assertEquals(0, repo.reads)
    }

    @Test fun lateOldTabAndSessionCloseCannotRevealRowsAndRefreshIsDeduplicated() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeContactsSession()
        val repo = FakeContactDirectory().apply {
            reading = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(listOf(ContactSummary("old", "peer", "Name", false)))
            }
            requestReading = { ClientResult.Ready(listOf(inbound())) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), session)
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Refresh)
        runCurrent()
        assertEquals(1, repo.reads)
        vm.onAction(ContactsAction.Tab(ContactsTab.STRANGERS))
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertEquals(listOf("request"), vm.state.value.rows.map { it.id })
        session.background()
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.selected)
    }

    @Test fun batchOnlySelectsRealInboundRowsAndNavigationIsOneShot() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply { requestReading = { ClientResult.Ready(listOf(inbound())) } }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Tab(ContactsTab.STRANGERS))
        runCurrent()
        vm.onAction(ContactsAction.Page(ContactsPage.BATCH))
        vm.onAction(ContactsAction.Toggle("invented"))
        assertTrue(vm.state.value.selectedIds.isEmpty())
        vm.onAction(ContactsAction.Toggle("request"))
        vm.onAction(ContactsAction.Operate(ContactOperation.BATCH_DECLINE))
        runCurrent()
        assertEquals(setOf("request"), vm.state.value.selectedIds)
        assertNotNull(vm.state.value.unavailable)
        val events = mutableListOf<ContactsEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ContactsAction.Back)
        vm.onAction(ContactsAction.Back)
        runCurrent()
        assertEquals(listOf(ContactsEvent.Back), events)
        collector.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test fun messageOpensTheContactsDirectConversationAndKeepsTheProfile() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply {
            capability = ClientResult.Ready(Unit)
            reading = { ClientResult.Ready(listOf(ContactSummary("link", "peer", "Name", false, "root"))) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        val events = mutableListOf<ContactsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(ContactsAction.Select("link"))
        vm.onAction(ContactsAction.Message)
        runCurrent()
        assertEquals(listOf("root"), repo.conversationRoots)
        assertEquals(1, events.size)
        assertTrue(events.single() is ContactsEvent.OpenChat)
        assertEquals("dm:peer", (events.single() as ContactsEvent.OpenChat).conversation)
        assertEquals(ContactsPage.DETAIL, vm.state.value.page)
        repo.conversationResult = ClientResult.Ready(null)
        vm.onAction(ContactsAction.Message)
        runCurrent()
        assertEquals(1, events.size)
        assertEquals(uiText(R.string.contacts_no_conversation), vm.state.value.unavailable)
    }
}
