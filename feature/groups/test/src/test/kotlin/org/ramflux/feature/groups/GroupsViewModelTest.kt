// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
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
class GroupsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.load(
        repo: FakeGroups,
        accounts: FakeGroupAccounts = FakeGroupAccounts(),
        session: FakeGroupSession = FakeGroupSession()
    ): GroupsViewModel {
        val vm = GroupsViewModel(repo, FakeGroupContacts(), accounts, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        runCurrent()
        vm.onAction(GroupsAction.Visible(true))
        runCurrent()
        return vm
    }
    private fun TestScope.confirm(vm: GroupsViewModel) {
        runCurrent()
        vm.onAction(GroupsAction.Confirm)
        runCurrent()
    }

    @Test fun creationNeedsHostConfirmThenReadbackAndCanonicalConversationBeforeOneNavigation() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val vm = load(repo)
        val events = mutableListOf<GroupsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(GroupsAction.Page(GroupsPage.CREATE))
        runCurrent()
        vm.onAction(GroupsAction.Name("New group"))
        vm.onAction(GroupsAction.Create)
        assertTrue(repo.changes.isEmpty())
        confirm(vm)
        assertEquals(1, repo.changes.size)
        assertEquals(listOf(GroupsEvent.OpenChat("a", "canonical", repo.changes.single().second.group)), events)
        assertFalse(repo.changes.single().first.isBlank())
    }

    @Test fun pendingOwnerAndFailedReadbackNeverNavigate() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups().apply { outcome = ClientResult.Ready(GroupOutcome("g", true, false, "r")) }
        val vm = load(repo)
        val events = mutableListOf<GroupsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(GroupsAction.Page(GroupsPage.CREATE))
        runCurrent()
        vm.onAction(GroupsAction.Name("Name"))
        vm.onAction(GroupsAction.Create)
        confirm(vm)
        assertTrue(events.isEmpty())
        assertNotNull(vm.state.value.notice)
        repo.outcome = ClientResult.Ready(GroupOutcome("g", false, false, null))
        repo.detailing = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        vm.onAction(GroupsAction.Create)
        confirm(vm)
        assertTrue(events.isEmpty())
        assertNotNull(vm.state.value.problem)
    }

    @Test fun invalidationRevokesConfirmationAndMemberControlsUntilAuthoritativeRefresh() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val vm = load(repo)
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBERS, "g"))
        runCurrent()
        assertTrue(vm.state.value.canManage)
        val id = vm.state.value.members.last().id
        vm.onAction(GroupsAction.Role(id, true))
        runCurrent()
        assertNotNull(vm.state.value.confirmation)
        val barrier = CompletableDeferred<Unit>()
        repo.detailing = {
            barrier.await()
            ClientResult.Ready(groupDetail(GroupRole.MEMBER))
        }
        repo.invalidations.emit(ClientResult.Ready(Unit))
        runCurrent()
        assertNull(vm.state.value.confirmation)
        assertFalse(vm.state.value.canManage)
        barrier.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.canManage)
        vm.onAction(GroupsAction.Role(id, true))
        confirm(vm)
        assertTrue(repo.changes.isEmpty())
    }

    @Test fun wrongAccountFailedReadsAndCloseConcealDataRatherThanFakeEmptySuccess() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeGroupAccounts()
        val session = FakeGroupSession()
        val repo = FakeGroups().apply {
            listing = {
                barrier.await()
                ClientResult.Ready(listOf(groupDetail().group))
            }
        }
        val vm = load(repo, accounts, session)
        accounts.value = ClientResult.Ready(AccountCatalog(emptyList(), null))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.accountRequired)
        assertTrue(vm.state.value.groups.isEmpty())
        assertFalse(vm.state.value.loaded)
        session.background()
        runCurrent()
        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test fun failedSnapshotIsNotEmptyAndVisibilityCancelsLateResults() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups().apply { listing = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) } }
        val vm = load(repo)
        assertFalse(vm.state.value.loaded)
        assertNotNull(vm.state.value.problem)
        val barrier = CompletableDeferred<Unit>()
        repo.listing = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Ready(listOf(groupDetail().group))
        }
        vm.onAction(GroupsAction.Refresh)
        runCurrent()
        vm.onAction(GroupsAction.Visible(false))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.groups.isEmpty())
    }
}
