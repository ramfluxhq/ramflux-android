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
            reading = { ClientResult.Ready(listOf(ContactSummary("link", "peer", "Name", false, "root"))) }
        }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        vm.onAction(ContactsAction.Select("link"))
        vm.onAction(ContactsAction.Page(ContactsPage.SAFETY))
        runCurrent()
        assertEquals(SafetyItem("1234 5678", "abcdef"), vm.state.value.safety)
        vm.onAction(ContactsAction.Operate(ContactOperation.VERIFY))
        runCurrent()
        assertEquals(uiText(R.string.contacts_safety_binding_unavailable), vm.state.value.unavailable)
        assertTrue(repo.submitted.isEmpty())
    }

    @Test fun hostDenialNeverSubmitsAndSingleConfirmationBindsExactTarget() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory()
        val vm = requestVm(repo)
        vm.onAction(ContactsAction.Operate(ContactOperation.ACCEPT))
        runCurrent()
        assertEquals(listOf("request"), vm.state.value.approval?.targets)
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

    @Test fun filteringIsLocalAndClearingRestoresTheNativeSnapshot() = runTest(timeout = 5.seconds) {
        val repo = FakeContactDirectory().apply { reading = { ClientResult.Ready(listOf(ContactSummary("one", "peer", "Name", false))) } }
        val vm = ContactsViewModel(repo, FakeContactsAccounts(), FakeContactsSession())
        observe(vm)
        runCurrent()
        val reads = repo.reads
        vm.onAction(ContactsAction.Query("missing"))
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        vm.onAction(ContactsAction.Query(""))
        runCurrent()
        assertEquals(listOf("one"), vm.state.value.rows.map { it.id })
        assertEquals(reads, repo.reads)
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
}
