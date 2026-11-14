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
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.load(
        repo: FakeAgentConfiguration,
        accounts: FakeAgentAccounts = FakeAgentAccounts(),
        session: FakeAgentSession = FakeAgentSession()
    ): AgentsViewModel {
        val vm = AgentsViewModel(repo, accounts, session)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        runCurrent()
        vm.onAction(AgentsAction.Visible(true))
        runCurrent()
        return vm
    }

    @Test fun everyModeUsesTypedUnavailableAndCannotInventAgents() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentConfiguration()
        val vm = load(repo)
        for (mode in AgentCapability.entries) {
            vm.onAction(AgentsAction.Mode(mode))
            runCurrent()
            assertTrue(vm.state.value.unavailable)
        }
        assertEquals(AgentCapability.entries.toSet(), repo.modes.toSet())
        assertFalse(vm.state.value.accountRequired)
    }

    @Test fun creationValidatesAdjacentFieldsBeforePreservingLiteralDraftAtDataBoundary() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentConfiguration()
        val vm = load(repo)
        vm.onAction(AgentsAction.Mode(AgentCapability.CREATE))
        runCurrent()
        vm.onAction(AgentsAction.Create)
        runCurrent()
        assertNotNull(vm.state.value.nameIssue)
        assertNotNull(vm.state.value.goalIssue)
        assertTrue(repo.creates.isEmpty())
        vm.onAction(AgentsAction.Name("Private literal"))
        vm.onAction(AgentsAction.Goal("Read messages"))
        vm.onAction(AgentsAction.Avatar(AgentAvatar.STAR))
        vm.onAction(AgentsAction.Create)
        runCurrent()
        assertEquals(listOf(AgentDraft("Private literal", "Read messages", AgentAvatar.STAR)), repo.creates)
        assertTrue(vm.state.value.unavailable)
        assertEquals("Private literal", vm.state.value.name)
    }

    @Test fun lateModeResultAndVisibilityCloseCannotRestorePrivateDraft() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentConfiguration()
        val vm = load(repo)
        val barrier = CompletableDeferred<Unit>()
        repo.checking = {
            withContext(NonCancellable) { barrier.await() }
            ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
        vm.onAction(AgentsAction.Mode(AgentCapability.PROFILE))
        runCurrent()
        vm.onAction(AgentsAction.Visible(false))
        barrier.complete(Unit)
        runCurrent()
        assertEquals(AgentsState(), vm.state.value)
    }

    @Test fun accountSwitchAndSessionCloseWipeDraftAndPreventCreation() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentConfiguration()
        val accounts = FakeAgentAccounts()
        val session = FakeAgentSession()
        val vm = load(repo, accounts, session)
        vm.onAction(AgentsAction.Mode(AgentCapability.CREATE))
        runCurrent()
        vm.onAction(AgentsAction.Name("Private"))
        vm.onAction(AgentsAction.Goal("Read"))
        accounts.result = ClientResult.Ready(AccountCatalog(emptyList(), null))
        vm.onAction(AgentsAction.Create)
        runCurrent()
        assertTrue(repo.creates.isEmpty())
        assertTrue(vm.state.value.accountRequired)
        assertEquals("", vm.state.value.name)
        session.background()
        runCurrent()
        assertEquals("", vm.state.value.name)
    }

    @Test fun readFailureIsDistinctAndOneShotBackDoesNotReplay() = runTest(timeout = 5.seconds) {
        val repo = FakeAgentConfiguration().apply {
            checking = { ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)) }
        }
        val vm = load(repo)
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.unavailable)
        val events = mutableListOf<AgentsEvent>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(AgentsAction.Back)
        runCurrent()
        collector.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(listOf(AgentsEvent.Back), events)
    }
}
