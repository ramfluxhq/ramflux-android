// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
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
import org.ramflux.feature.settings.impl.R
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private fun TestScope.observe(vm: SettingsViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
    }
    private fun failure() = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))

    @Test fun appearanceCanChangeWithoutAccountAndDoesNotRepeatPrivateReads() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts().apply { result = ClientResult.Ready(AccountCatalog(emptyList(), null)) }
        val devices = FakeDeviceDirectory()
        val status = FakeAccountStatus()
        val preferences = FakeSettingsAppearance()
        val vm = SettingsViewModel(accounts, devices, status, preferences, FakeSettingsSession())
        observe(vm)
        runCurrent()
        assertTrue(vm.state.value.accountRequired)
        assertEquals(0, devices.reads)
        vm.onAction(SettingsAction.Page(SettingsPage.APPEARANCE))
        vm.onAction(SettingsAction.Theme("dark"))
        runCurrent()
        assertEquals("dark", vm.state.value.selectedTheme)
        assertEquals(1, accounts.reads)
        assertEquals(0, status.reads)
        vm.onAction(SettingsAction.Theme(null))
        runCurrent()
        assertNull(vm.state.value.selectedTheme)
    }

    @Test fun failedPreferenceCommitKeepsPreviousSelectionAndVisibleError() = runTest(timeout = 5.seconds) {
        val preferences = FakeSettingsAppearance().apply { result = failure() }
        val vm = SettingsViewModel(FakeSettingsAccounts(), FakeDeviceDirectory(), FakeAccountStatus(), preferences, FakeSettingsSession())
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Theme("dark"))
        runCurrent()
        assertNull(vm.state.value.selectedTheme)
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.savingTheme)
        vm.onAction(SettingsAction.Theme("unknown"))
        runCurrent()
        assertEquals(1, preferences.writes)
    }

    @Test fun failedDeviceReadNeverClaimsLoadedEmptyAndAbsentSamplesAreNotHealthy() = runTest(timeout = 5.seconds) {
        val devices = FakeDeviceDirectory().apply { listing = { failure() } }
        val vm = SettingsViewModel(FakeSettingsAccounts(), devices, FakeAccountStatus(), FakeSettingsAppearance(), FakeSettingsSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.devicesLoaded)
        assertNotNull(vm.state.value.deviceProblem)
        assertEquals(uiText(R.string.settings_not_recorded), vm.state.value.status?.node)
        assertEquals(uiText(R.string.settings_not_recorded), vm.state.value.status?.sync)
    }

    @Test fun failedAccountDoesNotClaimAbsentAccountOrExposeOldDevices() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts().apply { result = failure() }
        val devices = FakeDeviceDirectory()
        val vm = SettingsViewModel(accounts, devices, FakeAccountStatus(), FakeSettingsAppearance(), FakeSettingsSession())
        observe(vm)
        runCurrent()
        assertFalse(vm.state.value.accountRequired)
        assertNotNull(vm.state.value.problem)
        assertTrue(vm.state.value.devices.isEmpty())
        assertEquals(0, devices.reads)
    }

    @Test fun changedAccountAndClosedSessionFenceLateDeviceRows() = runTest(timeout = 5.seconds) {
        val barrier = CompletableDeferred<Unit>()
        val accounts = FakeSettingsAccounts()
        val session = FakeSettingsSession()
        val devices = FakeDeviceDirectory().apply {
            listing = {
                withContext(NonCancellable) { barrier.await() }
                ClientResult.Ready(listOf(DeviceSummary("private", true, 1u)))
            }
        }
        val vm = SettingsViewModel(accounts, devices, FakeAccountStatus(), FakeSettingsAppearance(), session)
        observe(vm)
        runCurrent()
        accounts.result = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("b", "Other", true, "unlocked")), "b"))
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.accountRequired)
        vm.onAction(SettingsAction.Refresh)
        runCurrent()
        assertTrue(vm.state.value.devicesLoaded)
        session.background()
        runCurrent()
        assertTrue(vm.state.value.devices.isEmpty())
        assertNull(vm.state.value.accountLabel)
    }

    @Test fun backupAndRevokeStayTypedUnavailableWithoutOptimisticDeviceRemoval() = runTest(timeout = 5.seconds) {
        val devices = FakeDeviceDirectory().apply {
            listing = { ClientResult.Ready(listOf(DeviceSummary("local", true, 1u), DeviceSummary("other", false, 2u))) }
        }
        val vm = SettingsViewModel(FakeSettingsAccounts(), devices, FakeAccountStatus(), FakeSettingsAppearance(), FakeSettingsSession())
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Revoke("local"))
        runCurrent()
        assertEquals(0, devices.revokes)
        vm.onAction(SettingsAction.Revoke("other"))
        runCurrent()
        assertEquals(1, devices.revokes)
        assertEquals(2, vm.state.value.devices.size)
        assertNotNull(vm.state.value.unavailable)
        vm.onAction(SettingsAction.Page(SettingsPage.BACKUP))
        runCurrent()
        assertEquals(uiText(R.string.settings_custody_missing), vm.state.value.unavailable)
    }

    @Test fun navigationIsOneShotAndBackFromSubpageStaysInModule() = runTest(timeout = 5.seconds) {
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                FakeDeviceDirectory(),
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession()
            )
        observe(vm)
        runCurrent()
        val events = mutableListOf<SettingsEvent>()
        val collection = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(SettingsAction.Page(SettingsPage.DEVICES))
        vm.onAction(SettingsAction.Back)
        runCurrent()
        assertEquals(SettingsPage.GENERAL, vm.state.value.page)
        assertTrue(events.isEmpty())
        vm.onAction(SettingsAction.Connections)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Connections), events)
        collection.cancel()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        runCurrent()
        assertEquals(1, events.size)
    }

    @Test fun immediateMainStartupRetainsAccountForSubsequentDeviceAction() = runTest(timeout = 5.seconds) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val devices = FakeDeviceDirectory().apply { listing = { ClientResult.Ready(listOf(DeviceSummary("other", false, 1u))) } }
        val vm = SettingsViewModel(FakeSettingsAccounts(), devices, FakeAccountStatus(), FakeSettingsAppearance(), FakeSettingsSession())
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Revoke("other"))
        runCurrent()
        assertEquals(1, devices.revokes)
        assertNotNull(vm.state.value.unavailable)
    }
}
