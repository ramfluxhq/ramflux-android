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

    @Test fun failedLanguageWriteKeepsStoredSelectionAndNeverClaimsApplied() = runTest(timeout = 5.seconds) {
        val language = FakeSettingsLanguage().apply {
            result = ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
        val vm = SettingsViewModel(
            FakeSettingsAccounts(),
            FakeDeviceDirectory(),
            FakeAccountStatus(),
            FakeSettingsAppearance(),
            FakeSettingsSession(),
            language,
            FakeAccountSecurity()
        )
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Language(AppLanguage("zh-CN")))
        runCurrent()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.savedLanguage)
        assertEquals(AppLanguage("zh-CN"), vm.state.value.selectedLanguage)
        vm.onAction(SettingsAction.ApplyLanguage)
        runCurrent()
        assertEquals(AppLanguage.SYSTEM, vm.state.value.savedLanguage)
        assertNotNull(vm.state.value.problem)
        assertFalse(vm.state.value.savingLanguage)
    }

    @Test fun appearanceCanChangeWithoutAccountAndDoesNotRepeatPrivateReads() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts().apply { result = ClientResult.Ready(AccountCatalog(emptyList(), null)) }
        val devices = FakeDeviceDirectory()
        val status = FakeAccountStatus()
        val preferences = FakeSettingsAppearance()
        val vm =
            SettingsViewModel(accounts, devices, status, preferences, FakeSettingsSession(), FakeSettingsLanguage(), FakeAccountSecurity())
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
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                FakeDeviceDirectory(),
                FakeAccountStatus(),
                preferences,
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
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
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                devices,
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
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
        val vm =
            SettingsViewModel(
                accounts,
                devices,
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
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
        val vm =
            SettingsViewModel(
                accounts,
                devices,
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                session,
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
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
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                devices,
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Revoke("local"))
        runCurrent()
        assertEquals(0, devices.revokes)
        vm.onAction(SettingsAction.Revoke("other"))
        runCurrent()
        assertEquals("nothing is revoked before confirmation", 0, devices.revokes)
        assertEquals("other", vm.state.value.confirmRevoke)
        vm.onAction(SettingsAction.CancelRevoke)
        runCurrent()
        assertNull(vm.state.value.confirmRevoke)
        assertEquals(0, devices.revokes)
        vm.onAction(SettingsAction.Revoke("other"))
        vm.onAction(SettingsAction.ConfirmRevoke)
        runCurrent()
        assertEquals(1, devices.revokes)
        assertEquals(2, vm.state.value.devices.size)
        assertNotNull(vm.state.value.unavailable)
    }

    private fun vm(
        accounts: FakeSettingsAccounts = FakeSettingsAccounts(),
        session: FakeSettingsSession = FakeSettingsSession(),
        security: FakeAccountSecurity = FakeAccountSecurity()
    ) = SettingsViewModel(
        accounts,
        FakeDeviceDirectory(),
        FakeAccountStatus(),
        FakeSettingsAppearance(),
        session,
        FakeSettingsLanguage(),
        security
    )

    @Test fun lockNowCallsTheAppLockAndLeavesTheCoverToTheSessionState() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts()
        val session = FakeSettingsSession()
        val security = FakeAccountSecurity()
        val vm = vm(accounts, session, security)
        observe(vm)
        runCurrent()
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(SettingsAction.Page(SettingsPage.APP_LOCK))
        runCurrent()
        vm.onAction(SettingsAction.Lock)
        runCurrent()
        assertEquals(listOf("lock-app:a"), security.calls.filter { it.startsWith("lock-app") })
        assertTrue("account.lock is not used", accounts.locked.isEmpty())
        assertEquals(1, session.rechecks)
        assertTrue("the cover comes up, Settings does not navigate", events.isEmpty())
    }

    @Test fun lockNowRefusedAsDisabledTurnsOffAndSaysNotAvailableYet() = runTest(timeout = 5.seconds) {
        val security = FakeAccountSecurity().apply {
            lockApp = ClientResult.Failed(
                ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.REFUSED, statusName = "app_lock_disabled")
            )
        }
        val session = FakeSettingsSession()
        val vm = vm(session = session, security = security)
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Page(SettingsPage.APP_LOCK))
        runCurrent()
        vm.onAction(SettingsAction.Lock)
        runCurrent()
        assertFalse(vm.state.value.appLock.enabled)
        assertEquals(uiText(R.string.settings_blocked_by_sdk), vm.state.value.appLock.problem)
        assertNull(vm.state.value.problem)
        assertEquals(0, session.rechecks)
    }

    @Test fun lockNowDoesNothingWhileTheAppLockIsOff() = runTest(timeout = 5.seconds) {
        val security = FakeAccountSecurity().apply { appLock = ClientResult.Ready(AppLockStatus(false, 0L, AppLockState.UNLOCKED)) }
        val vm = vm(security = security)
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Page(SettingsPage.APP_LOCK))
        runCurrent()
        vm.onAction(SettingsAction.Lock)
        runCurrent()
        assertTrue(security.calls.none { it.startsWith("lock-app") })
    }

    @Test fun removeNeedsTheBoxActsOnTheFixedAccountAndNamesTheToast() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts()
        val vm = vm(accounts)
        observe(vm)
        runCurrent()
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(SettingsAction.AccountExit(SettingsAccountExit.REMOVE))
        runCurrent()
        assertEquals("a", vm.state.value.exitAccountId)
        assertEquals("Work", vm.state.value.exitAccountName)
        vm.onAction(SettingsAction.ConfirmAccountExit)
        runCurrent()
        assertTrue("without the box nothing is removed", accounts.removed.isEmpty())
        assertNull(vm.state.value.confirmExit)
        vm.onAction(SettingsAction.AccountExit(SettingsAccountExit.REMOVE))
        runCurrent()
        assertFalse(vm.state.value.exitUnderstood)
        vm.onAction(SettingsAction.ExitUnderstood(true))
        accounts.result = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Renamed", true, "unlocked")), "a"))
        vm.onAction(SettingsAction.Refresh)
        runCurrent()
        assertEquals("the name stays the one fixed at open", "Work", vm.state.value.exitAccountName)
        vm.onAction(SettingsAction.ConfirmAccountExit)
        runCurrent()
        assertEquals(listOf("a"), accounts.removed)
        assertEquals("the app window names the account the confirmation showed", listOf<String?>("Work"), accounts.announced)
        assertEquals(listOf(SettingsEvent.OpenAccount), events)
        assertFalse(vm.state.value.exitUnderstood)
    }

    @Test fun removeIsRefusedWhenTheActiveAccountMovedAwayFromTheFixedTarget() = runTest(timeout = 5.seconds) {
        val accounts = FakeSettingsAccounts()
        val vm = vm(accounts)
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.AccountExit(SettingsAccountExit.REMOVE))
        vm.onAction(SettingsAction.ExitUnderstood(true))
        accounts.result = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("b", "Other", true, "unlocked")), "b"))
        vm.onAction(SettingsAction.Refresh)
        runCurrent()
        vm.onAction(SettingsAction.ConfirmAccountExit)
        runCurrent()
        assertTrue(accounts.removed.isEmpty())
        assertNull(vm.state.value.confirmExit)
    }

    @Test fun navigationIsOneShotAndBackFromSubpageStaysInModule() = runTest(timeout = 5.seconds) {
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                FakeDeviceDirectory(),
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
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
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                devices,
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
        observe(vm)
        runCurrent()
        vm.onAction(SettingsAction.Revoke("other"))
        runCurrent()
        assertEquals("nothing is revoked before confirmation", 0, devices.revokes)
        assertEquals("other", vm.state.value.confirmRevoke)
        vm.onAction(SettingsAction.CancelRevoke)
        runCurrent()
        assertNull(vm.state.value.confirmRevoke)
        assertEquals(0, devices.revokes)
        vm.onAction(SettingsAction.Revoke("other"))
        vm.onAction(SettingsAction.ConfirmRevoke)
        runCurrent()
        assertEquals(1, devices.revokes)
        assertNotNull(vm.state.value.unavailable)
    }

    @Test fun storageOpensForTheActiveAccount() = runTest(timeout = 5.seconds) {
        val vm =
            SettingsViewModel(
                FakeSettingsAccounts(),
                FakeDeviceDirectory(),
                FakeAccountStatus(),
                FakeSettingsAppearance(),
                FakeSettingsSession(),
                FakeSettingsLanguage(),
                FakeAccountSecurity()
            )
        observe(vm)
        runCurrent()
        val events = mutableListOf<SettingsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events.add(it) } }
        vm.onAction(SettingsAction.Storage)
        runCurrent()
        assertEquals(listOf(SettingsEvent.Storage("a")), events)
    }
}
