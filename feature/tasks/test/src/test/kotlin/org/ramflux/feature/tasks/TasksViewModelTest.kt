// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.tasks
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
import org.ramflux.feature.tasks.impl.R
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.load(
        repo: FakeTasks,
        accounts: FakeTaskAccounts = FakeTaskAccounts(),
        session: FakeTaskSession = FakeTaskSession()
    ): TasksViewModel {
        val vm = TasksViewModel(repo, FakeTaskContexts(), accounts, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        vm.onAction(TasksAction.Visible(true))
        runCurrent()
        return vm
    }
    private fun TestScope.detail(vm: TasksViewModel) {
        vm.onAction(TasksAction.Conversation("c"))
        runCurrent()
        assertTrue("scoped list loaded", vm.state.value.loaded)
        assertEquals("c", vm.state.value.conversation)
        assertEquals(listOf("task"), vm.state.value.rows.map { it.id })
        vm.onAction(TasksAction.Select("task"))
        runCurrent()
    }

    @Test fun hostFactsDenyAndSingleConfirmBindExactSnapshotWithoutOptimisticTaskTransition() = runTest(timeout = 5.seconds) {
        val repo = FakeTasks()
        val vm = load(repo)
        detail(vm)
        assertTrue("detail fresh", vm.state.value.fresh)
        assertNotNull("detail present", vm.state.value.selected)
        vm.onAction(TasksAction.Prepare(TaskDecision.ACCEPT))
        runCurrent()
        assertNotNull(vm.state.value.confirmation)
        assertTrue(repo.mutations.isEmpty())
        vm.onAction(TasksAction.Deny)
        vm.onAction(TasksAction.Confirm("not-confirmed"))
        runCurrent()
        assertTrue(repo.mutations.isEmpty())
        vm.onAction(TasksAction.Prepare(TaskDecision.ACCEPT))
        runCurrent()
        val id = requireNotNull(vm.state.value.confirmation).id
        vm.onAction(TasksAction.Confirm("wrong"))
        runCurrent()
        assertTrue(repo.mutations.isEmpty())
        vm.onAction(TasksAction.Confirm(id))
        runCurrent()
        assertEquals(TaskAttempt(id, taskRecord(), TaskDecision.ACCEPT), repo.mutations.single())
        assertEquals(uiText(R.string.tasks_proposed), vm.state.value.selected?.status)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun unknownOriginalIntentSurvivesRecreationAndReconcileNeverCreatesAnotherIdentity() = runTest(timeout = 5.seconds) {
        val repo = FakeTasks().apply {
            mutating = { ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN)) }
        }
        val vm = load(repo)
        detail(vm)
        vm.onAction(TasksAction.Prepare(TaskDecision.CANCEL))
        runCurrent()
        val id = requireNotNull(vm.state.value.confirmation).id
        vm.onAction(TasksAction.Confirm(id))
        runCurrent()
        val original = repo.mutations.single()
        assertEquals(id, vm.state.value.pendingId)
        vm.onAction(TasksAction.Visible(false))
        runCurrent()
        val restored = load(repo)
        assertEquals(id, restored.state.value.pendingId)
        restored.onAction(TasksAction.Reconcile("wrong"))
        assertNull(restored.state.value.confirmation)
        restored.onAction(TasksAction.Reconcile(id))
        restored.onAction(TasksAction.Confirm(id))
        runCurrent()
        assertEquals(listOf(original, original), repo.mutations)
    }

    @Test fun invalidationRevokesPreparedApprovalBeforeStaleConfirmationCanSubmit() = runTest(timeout = 5.seconds) {
        val repo = FakeTasks()
        val vm = load(repo)
        detail(vm)
        vm.onAction(TasksAction.Prepare(TaskDecision.ACCEPT))
        runCurrent()
        val id = requireNotNull(vm.state.value.confirmation).id
        val barrier = CompletableDeferred<Unit>()
        repo.detailing = {
            barrier.await()
            ClientResult.Ready(taskRecord(8u, "running"))
        }
        repo.invalidations.emit(ClientResult.Ready(Unit))
        runCurrent()
        assertNull(vm.state.value.confirmation)
        assertFalse(vm.state.value.fresh)
        vm.onAction(TasksAction.Confirm(id))
        runCurrent()
        assertTrue(repo.mutations.isEmpty())
        barrier.complete(Unit)
        runCurrent()
        assertEquals(8uL, vm.state.value.selected?.version)
    }

    @Test fun accountChangeAndSessionCloseConcealPrivateRowsBeforeMutation() = runTest(timeout = 5.seconds) {
        val repo = FakeTasks()
        val accounts = FakeTaskAccounts()
        val session = FakeTaskSession()
        val vm = load(repo, accounts, session)
        detail(vm)
        vm.onAction(TasksAction.Prepare(TaskDecision.ACCEPT))
        runCurrent()
        val id = requireNotNull(vm.state.value.confirmation).id
        accounts.result = ClientResult.Ready(AccountCatalog(emptyList(), null))
        vm.onAction(TasksAction.Confirm(id))
        runCurrent()
        assertTrue(repo.mutations.isEmpty())
        assertTrue(vm.state.value.accountRequired)
        assertNull(vm.state.value.selected)
        session.background()
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun cursorIsOpaqueAndCyclesOrFailedReadsNeverBecomeEmptySuccess() = runTest(timeout = 5.seconds) {
        val seen = mutableListOf<String?>()
        val repo = FakeTasks().apply {
            paging = { cursor ->
                seen.add(cursor)
                ClientResult.Ready(TaskPage(listOf(taskRecord().copy(id = if (cursor == null) "one" else "two")), "opaque"))
            }
        }
        val vm = load(repo)
        vm.onAction(TasksAction.Conversation("c"))
        runCurrent()
        vm.onAction(TasksAction.More)
        runCurrent()
        assertEquals(listOf(null, "opaque"), seen)
        vm.onAction(TasksAction.More)
        runCurrent()
        assertEquals(2, seen.size)
        assertNotNull(vm.state.value.problem)
        repo.paging = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        vm.onAction(TasksAction.Refresh)
        runCurrent()
        assertFalse(vm.state.value.loaded)
    }

    @Test fun invisibleLateResultIsDiscardedAndUnavailableCannotGrantAuthority() = runTest(timeout = 5.seconds) {
        val repo = FakeTasks()
        val vm = load(repo)
        vm.onAction(TasksAction.Unavailable)
        runCurrent()
        assertTrue(vm.state.value.unavailable)
        assertTrue(repo.mutations.isEmpty())
        val barrier = CompletableDeferred<Unit>()
        repo.paging = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Ready(TaskPage(listOf(taskRecord()), null))
        }
        vm.onAction(TasksAction.Conversation("c"))
        runCurrent()
        vm.onAction(TasksAction.Visible(false))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.selected)
    }

    @Test fun compactReceiptKeepsBindingAndLocalizedStatusWithoutGrantingAnAction() = runTest(timeout = 5.seconds) {
        val receipt = TaskPermission(
            "receipt:r", "r", TaskPermissionKind.RECEIPT, null, null, "approved", 2000000000,
            null, null, null, emptyList(), null, null, null, "decision", "grant", "c", "task", "step", ULong.MAX_VALUE,
            "human", "agent", "executor", "submitted", "retry_exhausted"
        )
        val repo = FakeTasks().apply { permissionRows = ClientResult.Ready(listOf(receipt)) }
        val vm = load(repo)
        vm.onAction(TasksAction.Permissions)
        runCurrent()
        vm.onAction(TasksAction.Permission("receipt:r"))
        runCurrent()
        val row = requireNotNull(vm.state.value.selectedPermission)
        assertEquals(uiText(R.string.tasks_receipt), row.kind)
        assertTrue(row.facts.contains(uiText(R.string.tasks_version, org.ramflux.designsystem.UiText.Unsigned(ULong.MAX_VALUE))))
        assertTrue(row.facts.contains(uiText(R.string.tasks_recorded_status, uiText(R.string.tasks_approved))))
        vm.onAction(TasksAction.Unavailable)
        runCurrent()
        assertTrue(vm.state.value.unavailable)
        assertTrue(repo.mutations.isEmpty())
    }

    @Test fun backEventHasNoStateReplay() = runTest(timeout = 5.seconds) {
        val vm = load(FakeTasks())
        val events = mutableListOf<TasksEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(TasksAction.Back)
        runCurrent()
        collector.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(listOf(TasksEvent.Back), events)
    }
}
