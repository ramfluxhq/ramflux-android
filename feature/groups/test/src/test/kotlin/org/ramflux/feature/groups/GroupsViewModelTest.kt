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
import org.ramflux.feature.mlsgroups.MlsCreateAction
import org.ramflux.feature.mlsgroups.MlsCreateEvent
import org.ramflux.feature.mlsgroups.MlsCreateViewModel
import org.ramflux.feature.mlsgroups.MlsGroupsAction
import org.ramflux.feature.mlsgroups.MlsGroupsViewModel
import org.ramflux.feature.mlsgroups.MlsInfoAction
import org.ramflux.feature.mlsgroups.MlsInfoEvent
import org.ramflux.feature.mlsgroups.MlsInfoViewModel
import org.ramflux.feature.mlsgroups.MlsInviteAction
import org.ramflux.feature.mlsgroups.MlsInviteEvent
import org.ramflux.feature.mlsgroups.MlsInviteViewModel
import org.ramflux.feature.mlsgroups.MlsMembersAction
import org.ramflux.feature.mlsgroups.MlsMembersEvent
import org.ramflux.feature.mlsgroups.MlsMembersUi
import org.ramflux.feature.mlsgroups.MlsMembersViewModel
import org.ramflux.feature.mlsgroups.MlsRequestsAction
import org.ramflux.feature.mlsgroups.MlsRequestsEvent
import org.ramflux.feature.mlsgroups.MlsRequestsViewModel
import org.ramflux.feature.mlsgroups.MlsSettingsAction
import org.ramflux.feature.mlsgroups.MlsSettingsEvent
import org.ramflux.feature.mlsgroups.MlsSettingsUi
import org.ramflux.feature.mlsgroups.MlsSettingsViewModel
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
        session: FakeGroupSession = FakeGroupSession(),
        people: FakeGroupContacts = FakeGroupContacts()
    ): GroupsViewModel {
        val vm = GroupsViewModel(repo, people, accounts, session)
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

    @Test fun fiftyThousandMemberRosterStaysWindowedInBothDirections() = runTest(timeout = 30.seconds) {
        val own = GroupMember("self", "root", "own", GroupRole.OWNER, true)
        val roster = listOf(own) + List(49_999) { GroupMember("peer-$it", "peer-root-$it", "peer-device-$it", GroupRole.MEMBER, true) }
        val repo = FakeGroups().apply { rostering = { ClientResult.Ready(roster) } }
        val vm = load(repo)
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBERS, "g"))
        runCurrent()
        assertEquals(100, vm.state.value.members.size)
        assertTrue(vm.state.value.paging(GroupList.MEMBERS).more)
        val started = System.nanoTime()
        var slowest = 0L
        repeat(499) {
            val before = System.nanoTime()
            vm.onAction(GroupsAction.More(GroupList.MEMBERS))
            runCurrent()
            slowest = maxOf(slowest, System.nanoTime() - before)
            assertTrue(vm.state.value.members.size <= 600)
        }
        val total = (System.nanoTime() - started) / 1_000_000
        println("group members 50k: 500 pages in $total ms, slowest page ${slowest / 1_000_000} ms, window ${vm.state.value.members.size}")
        assertEquals("peer-device-49998", vm.state.value.members.last().device)
        assertFalse(vm.state.value.paging(GroupList.MEMBERS).more)
        assertTrue(vm.state.value.paging(GroupList.MEMBERS).before)
        vm.onAction(GroupsAction.More(GroupList.MEMBERS, forward = false))
        runCurrent()
        assertTrue(vm.state.value.members.size <= 600)
        assertEquals("peer-device-49299", vm.state.value.members.first().device)
        // Only peers are manageable; the owner's own row never is.
        assertTrue(vm.state.value.members.all { it.manageable })
        vm.onAction(GroupsAction.Query("peer-4999"))
        advanceTimeBy(300)
        runCurrent()
        assertEquals(10, vm.state.value.members.size)
        assertEquals(null, repo.cursors.last().second)
    }

    @Test fun groupListHasNoRowCapAndPagesThroughCursors() = runTest(timeout = 10.seconds) {
        val rows = List(1_500) { GroupSummary("g$it", "Group $it", 1u, listOf("own")) }
        val repo = FakeGroups().apply { listing = { ClientResult.Ready(rows) } }
        val vm = load(repo)
        assertTrue(vm.state.value.loaded)
        assertNull(vm.state.value.problem)
        assertEquals(100, vm.state.value.groups.size)
        repeat(14) {
            vm.onAction(GroupsAction.More(GroupList.GROUPS))
            runCurrent()
        }
        assertEquals("g1499", vm.state.value.groups.last().id)
        assertTrue(vm.state.value.groups.size <= 600)
    }

    @Test fun memberPageChangesRoleAndRemovesAfterConfirmationThenReturnsToRoster() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val vm = load(repo)
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBERS, "g"))
        runCurrent()
        val own = vm.state.value.members.first()
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBER, own.id))
        runCurrent()
        assertFalse(vm.state.value.selectedMember!!.manageable)
        vm.onAction(GroupsAction.Remove(own.id))
        runCurrent()
        assertNull(vm.state.value.confirmation)
        vm.onAction(GroupsAction.Back)
        runCurrent()
        val peer = vm.state.value.members.last()
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBER, peer.id))
        runCurrent()
        assertEquals(GroupsPage.MEMBER, vm.state.value.page)
        assertTrue(vm.state.value.selectedMember!!.manageable)
        vm.onAction(GroupsAction.Role(peer.id, true))
        confirm(vm)
        assertEquals(GroupOperation.ROLE to GroupRole.ADMIN, repo.changes.last().second.let { it.operation to it.role })
        vm.onAction(GroupsAction.Remove(peer.id))
        runCurrent()
        assertNotNull(vm.state.value.confirmation)
        confirm(vm)
        assertEquals(GroupOperation.REMOVE, repo.changes.last().second.operation)
        assertEquals("peer", repo.changes.last().second.member?.device)
        assertEquals(GroupsPage.MEMBERS, vm.state.value.page)
    }

    @Test fun transferExplainsWhyItIsUnavailableWithoutAnyChange() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val vm = load(repo)
        vm.onAction(GroupsAction.Page(GroupsPage.DETAIL, "g"))
        runCurrent()
        vm.onAction(GroupsAction.Unavailable(GroupControl.TRANSFER))
        runCurrent()
        assertNotNull(vm.state.value.notice)
        assertNull(vm.state.value.confirmation)
        assertTrue(repo.changes.isEmpty())
    }

    @Test fun invitePickerReadsAcceptedContactPagesAndAddsTheRootQualifiedContact() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val people = FakeGroupContacts().apply {
            this.people = listOf(
                ContactSummary("link", "peer-new", "New peer", true, "new-root"),
                ContactSummary("rootless", "x", "No root", false)
            )
        }
        val vm = load(repo, people = people)
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBERS, "g"))
        runCurrent()
        vm.onAction(GroupsAction.Page(GroupsPage.INVITE, "g"))
        runCurrent()
        assertEquals(listOf(ContactListScope.ACCEPTED), people.scopes)
        assertEquals(listOf("link"), vm.state.value.contacts.map { it.id })
        vm.onAction(GroupsAction.Add("link"))
        confirm(vm)
        val change = repo.changes.single().second
        assertEquals(GroupOperation.ADD to "new-root", change.operation to change.root)
    }

    @Test fun enteringAtGroupDetailsReturnsToTheCallerOnBackButMembersStillBackToDetails() = runTest(timeout = 5.seconds) {
        val repo = FakeGroups()
        val vm = load(repo)
        val events = mutableListOf<GroupsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(GroupsAction.Enter("elsewhere"))
        runCurrent()
        assertEquals(GroupsPage.DETAIL, vm.state.value.page)
        assertEquals("elsewhere", vm.state.value.selected)
        vm.onAction(GroupsAction.Page(GroupsPage.MEMBERS, "elsewhere"))
        runCurrent()
        vm.onAction(GroupsAction.Back)
        runCurrent()
        assertEquals(GroupsPage.DETAIL, vm.state.value.page)
        assertTrue(events.isEmpty())
        vm.onAction(GroupsAction.Back)
        runCurrent()
        assertEquals(listOf(GroupsEvent.Back), events)
    }

    // ---- MLS group screens: nothing survives a lock (F-1), the create form across accounts (F-2), a screen opened while its
    // account is purged (F-3), and no stale Back for a screen that is not shown (F-4).

    private class MlsRepo : MlsGroupRepository by UnavailableMlsGroups {
        var lists = 0
        var infos = 0
        override suspend fun list(account: String, cursor: String?): ClientResult<MlsGroupPage<MlsGroup>> {
            lists += 1
            return ClientResult.Ready(MlsGroupPage(emptyList(), null))
        }
        override suspend fun info(account: String, groupId: String): ClientResult<MlsGroup> {
            infos += 1
            return UnavailableMlsGroups.info(account, groupId)
        }
    }

    private fun <T> TestScope.taking(flow: Flow<T>): MutableList<T> {
        val got = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { got += it } }
        return got
    }

    @Test fun mlsGroupsDropTheJoinSheetAndTypedLinkOnLockAndReadTheListAgainAfterTheUnlock() = runTest(timeout = 5.seconds) {
        val repo = MlsRepo()
        val session = MlsGroupSession(repo, backgroundScope)
        val vm = MlsGroupsViewModel(session)
        taking(vm.state)
        taking(vm.events)
        vm.onAction(MlsGroupsAction.Open("a"))
        vm.onAction(MlsGroupsAction.OpenJoin)
        vm.onAction(MlsGroupsAction.Link("rfinv-typed"))
        runCurrent()
        assertEquals("rfinv-typed", vm.state.value.join?.link)
        val reads = repo.lists
        session.appLocked("a")
        runCurrent()
        assertNull(vm.state.value.join)
        assertEquals("nothing is read while the account is purged", reads, repo.lists)
        session.appUnlocked()
        runCurrent()
        assertEquals("the list on screen reads again once admitted", reads + 1, repo.lists)
        assertNull(vm.state.value.join)
    }

    @Test fun mlsCreateKeepsNoFormAcrossAnAccountChangeAndClosesOnLock() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsCreateViewModel(session)
        taking(vm.state)
        val events = taking(vm.events)
        vm.onAction(MlsCreateAction.Open("a"))
        vm.onAction(MlsCreateAction.Name("Team A"))
        vm.onAction(MlsCreateAction.Approval(false))
        runCurrent()
        assertEquals("Team A", vm.state.value.name)
        vm.onAction(MlsCreateAction.Open("b"))
        runCurrent()
        assertEquals("", vm.state.value.name)
        assertTrue(vm.state.value.approval)
        vm.onAction(MlsCreateAction.Name("Team B"))
        runCurrent()
        session.appLocked("b")
        runCurrent()
        assertEquals("", vm.state.value.name)
        assertEquals(listOf(MlsCreateEvent.Back), events)
    }

    @Test fun mlsInfoDropsTheLeaveConfirmationOnLockAndCloses() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsInfoViewModel(session)
        taking(vm.state)
        val events = taking(vm.events)
        vm.onAction(MlsInfoAction.Open("a", "g"))
        vm.onAction(MlsInfoAction.AskLeave)
        runCurrent()
        assertTrue(vm.state.value.confirmLeave)
        session.appLocked("a")
        runCurrent()
        assertFalse(vm.state.value.confirmLeave)
        assertEquals(listOf(MlsInfoEvent.Back), events)
    }

    @Test fun mlsSettingsDropTheTypedNameOnLockAndClose() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsSettingsViewModel(session)
        val events = taking(vm.events)
        vm.onAction(MlsSettingsAction.Open("a", "g"))
        vm.onAction(MlsSettingsAction.Draft("Typed name"))
        runCurrent()
        assertEquals("Typed name", vm.state.value.draft)
        session.appLocked("a")
        runCurrent()
        assertEquals(MlsSettingsUi(), vm.state.value)
        assertEquals(listOf(MlsSettingsEvent.Back), events)
    }

    @Test fun mlsMembersDropTheSearchAndMenuOnLockAndClose() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsMembersViewModel(session)
        val events = taking(vm.events)
        vm.onAction(MlsMembersAction.Open("a", "g"))
        vm.onAction(MlsMembersAction.Search("ana"))
        vm.onAction(MlsMembersAction.OpenMenu("anchor-1"))
        runCurrent()
        assertEquals("ana", vm.state.value.query)
        session.appLocked("a")
        runCurrent()
        assertEquals(MlsMembersUi(), vm.state.value)
        assertEquals(listOf(MlsMembersEvent.Back), events)
    }

    @Test fun mlsRequestsCloseOnLock() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsRequestsViewModel(session)
        val events = taking(vm.events)
        vm.onAction(MlsRequestsAction.Open("a", "g"))
        runCurrent()
        session.appLocked("a")
        runCurrent()
        assertEquals(listOf(MlsRequestsEvent.Back), events)
    }

    @Test fun mlsInviteDropsTheRevokeConfirmationOnLockAndCloses() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        val vm = MlsInviteViewModel(session, InMemoryMlsGroupNotices())
        val events = taking(vm.events)
        vm.onAction(MlsInviteAction.Open("a", "g", fromCreate = false))
        vm.onAction(MlsInviteAction.AskRevoke("inv-1"))
        runCurrent()
        assertEquals("inv-1", vm.state.value.confirmRevoke)
        session.appLocked("a")
        runCurrent()
        assertNull(vm.state.value.confirmRevoke)
        assertNull(vm.state.value.link)
        assertEquals(listOf(MlsInviteEvent.Back), events)
    }

    @Test fun anMlsScreenNotShownAtTheLockIsNotClosedWhenOpenedAgainAfterTheUnlock() = runTest(timeout = 5.seconds) {
        val repo = MlsRepo()
        val session = MlsGroupSession(repo, backgroundScope)
        val vm = MlsInfoViewModel(session)
        vm.onAction(MlsInfoAction.Open("a", "g"))
        vm.onAction(MlsInfoAction.AskLeave)
        runCurrent()
        session.appLocked("a")
        runCurrent()
        session.appUnlocked()
        runCurrent()
        // The screen comes back: no Back is waiting for it, and it reads the group again.
        val events = taking(vm.events)
        val reads = repo.infos
        vm.onAction(MlsInfoAction.Open("a", "g"))
        runCurrent()
        assertEquals(emptyList<MlsInfoEvent>(), events)
        assertEquals(reads + 1, repo.infos)
        assertFalse(vm.state.value.confirmLeave)
    }

    @Test fun anMlsScreenOpenedWhileItsAccountIsPurgedClosesOnceTheAccountIsAdmitted() = runTest(timeout = 5.seconds) {
        val session = MlsGroupSession(MlsRepo(), backgroundScope)
        session.appLocked("a")
        val vm = MlsInfoViewModel(session)
        val events = taking(vm.events)
        vm.onAction(MlsInfoAction.Open("a", "g"))
        runCurrent()
        assertEquals(emptyList<MlsInfoEvent>(), events)
        session.appUnlocked()
        runCurrent()
        assertEquals(listOf(MlsInfoEvent.Back), events)
    }
}
