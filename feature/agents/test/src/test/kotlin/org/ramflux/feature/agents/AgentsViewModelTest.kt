// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
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
class AgentsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(UnconfinedTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.open(
        repo: FakeAgentDirectory = FakeAgentDirectory(),
        accounts: FakeAgentAccounts = FakeAgentAccounts(),
        session: FakeAgentSession = FakeAgentSession()
    ): AgentsViewModel {
        val vm = AgentsViewModel(repo, accounts, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        vm.onAction(AgentsAction.Visible(true))
        settle()
        return vm
    }

    /** The filter runs on Dispatchers.Default; wait until its result is published. */
    private fun TestScope.settle() {
        runCurrent()
        Thread.sleep(SETTLE_MS)
        runCurrent()
    }

    @Test fun directoryListsAgentsAndFiltersOnlyLoadedRows() = runTest(timeout = 5.seconds) {
        val vm = open()
        assertTrue(vm.state.value.loaded)
        assertEquals(listOf("nova", "atlas", "mira"), vm.state.value.rows.map { it.id })
        vm.onAction(AgentsAction.Query("at"))
        settle()
        assertEquals(listOf("atlas"), vm.state.value.rows.map { it.id })
        vm.onAction(AgentsAction.Query("zzz"))
        settle()
        assertTrue(vm.state.value.rows.isEmpty())
        assertEquals(3, vm.state.value.loadedCount)
    }

    @Test fun fiftyThousandAgentSourceStaysWindowedInBothDirections() = runTest(timeout = 60.seconds) {
        val repo = FakeAgentDirectory().apply { records = List(50_000) { agentRecord("agent-$it") } }
        val vm = open(repo)
        val started = System.nanoTime()
        var slowest = 0L
        var pages = 1
        while (vm.state.value.hasMore && pages < 6_250) {
            val before = System.nanoTime()
            vm.onAction(AgentsAction.More())
            runCurrent()
            slowest = maxOf(slowest, System.nanoTime() - before)
            pages++
            assertTrue(vm.state.value.loadedCount <= 96)
        }
        settle()
        val total = (System.nanoTime() - started) / 1_000_000
        println("agents 50k: $pages pages of 8 in $total ms, slowest page ${slowest / 1_000_000} ms, window ${vm.state.value.loadedCount}")
        assertEquals(6_250, pages)
        assertEquals("agent-49999", vm.state.value.rows.last().id)
        assertTrue(vm.state.value.hasBefore)
        vm.onAction(AgentsAction.More(forward = false))
        settle()
        assertEquals("agent-49896", vm.state.value.rows.first().id)
        assertTrue(vm.state.value.loadedCount <= 96)
    }

    @Test fun refreshAsksWatchAndReloadsOnlyWhenSomethingChanged() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentDirectory()
        val vm = open(repo)
        val reads = repo.cursors.size
        vm.onAction(AgentsAction.Refresh)
        settle()
        assertEquals(1, repo.watches)
        assertEquals(reads, repo.cursors.size)
        repo.changed = listOf(agentRecord("nova", "Nova renamed"))
        repo.records = listOf(agentRecord("nova", "Nova renamed"))
        vm.onAction(AgentsAction.Refresh)
        settle()
        assertEquals(reads + 1, repo.cursors.size)
        assertEquals("Nova renamed", vm.state.value.rows.single().name)
    }

    @Test fun editAndDeleteNeedHostConfirmationAndDeleteStatesWhatStays() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentDirectory()
        val vm = open(repo)
        vm.onAction(AgentsAction.Select("nova"))
        settle()
        assertEquals(AgentsPage.PROFILE, vm.state.value.page)
        vm.onAction(AgentsAction.Edit)
        vm.onAction(AgentsAction.Name("Nova 2"))
        vm.onAction(AgentsAction.Responsibility(" "))
        vm.onAction(AgentsAction.Save)
        settle()
        assertNotNull(vm.state.value.responsibilityIssue)
        assertNull(vm.state.value.review)
        vm.onAction(AgentsAction.Responsibility("Plans"))
        vm.onAction(AgentsAction.Save)
        settle()
        assertNotNull(vm.state.value.review)
        assertTrue(repo.edits.isEmpty())
        vm.onAction(AgentsAction.Deny)
        settle()
        assertTrue(repo.edits.isEmpty())
        vm.onAction(AgentsAction.Save)
        vm.onAction(AgentsAction.Confirm)
        settle()
        assertEquals("nova" to AgentPersona("Nova 2", "orbit", "Plans"), repo.edits.single())
        assertEquals(AgentsPage.PROFILE, vm.state.value.page)
        assertEquals("Nova 2", vm.state.value.selected?.row?.name)
        vm.onAction(AgentsAction.Delete)
        settle()
        assertNotNull(vm.state.value.review)
        assertTrue(repo.deletes.isEmpty())
        vm.onAction(AgentsAction.Confirm)
        settle()
        assertEquals(listOf("nova"), repo.deletes)
        assertEquals(AgentsPage.LIST, vm.state.value.page)
        assertFalse(vm.state.value.rows.any { it.id == "nova" })
        assertNotNull(vm.state.value.notice)
    }

    @Test fun messageOpensTheExistingConversationOrExplainsThereIsNone() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentDirectory()
        val vm = open(repo)
        val events = mutableListOf<AgentsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(AgentsAction.Select("nova"))
        settle()
        vm.onAction(AgentsAction.Message)
        settle()
        assertEquals(listOf(AgentsEvent.OpenChat("a", "dm:nova")), events)
        repo.dm = null
        vm.onAction(AgentsAction.Message)
        settle()
        assertEquals(1, events.size)
        assertNotNull(vm.state.value.notice)
    }

    @Test fun pendingCreationIsShownReadOnlyAndFailedReadIsNotAnEmptyDirectory() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentDirectory().apply {
            waiting = PendingAgentCreation("request-1", "authorization_pending", "new", "Planner", 2_000_000_000)
        }
        val vm = open(repo)
        assertEquals("Planner", vm.state.value.pending?.name)
        repo.listing = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        val failing = open(repo)
        assertFalse(failing.state.value.loaded)
        assertNotNull(failing.state.value.problem)
    }

    @Test fun lockedAccountAndClosedSessionConcealAgents() = runTest(timeout = 5.seconds) {
        val accounts = FakeAgentAccounts().apply { result = ClientResult.Ready(AccountCatalog(emptyList(), null)) }
        val session = FakeAgentSession()
        val vm = open(accounts = accounts, session = session)
        assertTrue(vm.state.value.accountRequired)
        assertTrue(vm.state.value.rows.isEmpty())
        session.background()
        settle()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNotNull(vm.state.value.problem)
    }

    private companion object {
        const val SETTLE_MS = 30L
    }
}
