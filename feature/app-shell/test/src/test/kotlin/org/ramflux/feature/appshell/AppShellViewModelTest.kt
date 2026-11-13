// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
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
import org.ramflux.feature.app_shell.impl.R

@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) {
            Dispatchers.setMain(dispatcher)
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: AppShellViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }

    @Test fun connectionFailureAndUnavailableAreStateRatherThanFabricatedReadyData() = runTest(timeout = 5.seconds) {
        val session = FakeSession()
        val vm = AppShellViewModel(session, FakeAppearance())
        observe(vm)
        session.status.value = ClientSnapshot(
            ClientPhase.FAILED,
            problem = ClientProblem(ClientFailureKind.INCOMPATIBLE, -4, ClientOutcome.NONE)
        )
        runCurrent()
        assertFalse(vm.state.value.ready)
        assertTrue(vm.state.value.canRetry)
        assertEquals(uiText(R.string.ui_this_app_and_its_account_library_are_not), vm.state.value.error?.description)
        session.status.value = ClientSnapshot(ClientPhase.OPEN, 1)
        runCurrent()
        vm.onAction(AppShellAction.Open(ClientFeature.ONBOARDING))
        runCurrent()
        assertEquals(uiText(R.string.ui_not_available_yet), vm.state.value.notice?.title)
        assertTrue(vm.state.value.ready)
    }

    @Test fun duplicateRetrySharesOneOwnedConnectionAttempt() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeSession().apply { opening = barrier }
        val vm = AppShellViewModel(session, FakeAppearance())
        observe(vm)
        vm.onAction(AppShellAction.Retry)
        vm.onAction(AppShellAction.Retry)
        runCurrent()
        assertEquals(1, session.connects)
        assertTrue(vm.state.value.loading)
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.ready)
    }

    @Test fun staleAvailabilityCannotNavigateOverANewerSelection() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeSession().apply {
            status.value = ClientSnapshot(ClientPhase.OPEN, 1)
            available = { feature ->
                if (feature == ClientFeature.ONBOARDING) {
                    withContext(NonCancellable) { barrier.await() }
                    ClientResult.Ready(Unit)
                } else {
                    ClientResult.Unavailable(feature)
                }
            }
        }
        val vm = AppShellViewModel(session, FakeAppearance())
        observe(vm)
        val events = mutableListOf<AppShellEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        vm.onAction(AppShellAction.Open(ClientFeature.ONBOARDING))
        runCurrent()
        vm.onAction(AppShellAction.Open(ClientFeature.SETTINGS))
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(events.isEmpty())
        assertEquals(uiText(R.string.ui_not_available_yet), vm.state.value.notice?.title)
    }

    @Test fun installedFeatureUsesOneShotEventAndPreferenceFailureDoesNotClaimSuccess() = runTest(timeout = 5.seconds) {
        val session = FakeSession().apply {
            status.value = ClientSnapshot(ClientPhase.OPEN, 1)
            available = { ClientResult.Ready(Unit) }
        }
        val preferences = FakeAppearance().apply { failure = ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE) }
        val vm = AppShellViewModel(session, preferences)
        observe(vm)
        val event = async { vm.events.first() }
        vm.onAction(AppShellAction.Open(ClientFeature.CONVERSATIONS))
        runCurrent()
        assertEquals(AppShellEvent.Navigate("conversations"), event.await())
        vm.onAction(AppShellAction.ChangeAppearance(Appearance.DARK))
        runCurrent()
        assertEquals(Appearance.SYSTEM, vm.state.value.appearance)
        assertNotNull(vm.state.value.notice)
        preferences.failure = null
        vm.onAction(AppShellAction.ChangeAppearance(Appearance.DARK))
        runCurrent()
        assertEquals(Appearance.DARK, vm.state.value.appearance)
        assertFalse(vm.state.value.savingAppearance)
    }

    @Test fun failedPreferenceReadIsVisibleAndCannotClaimALoadedSetting() = runTest(timeout = 5.seconds) {
        val preferences = FakeAppearance().apply {
            appearance.value = AppearanceSnapshot(
                loading = false,
                problem = ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE)
            )
        }
        val vm = AppShellViewModel(FakeSession(), preferences)
        observe(vm)
        runCurrent()
        assertNotNull(vm.state.value.error)
    }

    @Test fun clearingViewModelCancelsPendingNavigationEvenIfRepositoryFinishesLate() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val session = FakeSession().apply {
            status.value = ClientSnapshot(ClientPhase.OPEN, 1)
            available = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(Unit)
            }
        }
        val vm = AppShellViewModel(session, FakeAppearance())
        observe(vm)
        val events = mutableListOf<AppShellEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        val owner = androidx.lifecycle.ViewModelStore()
        owner.put("shell", vm)
        vm.onAction(AppShellAction.Open(ClientFeature.ONBOARDING))
        runCurrent()
        owner.clear()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(events.isEmpty())
    }
}
