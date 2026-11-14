// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
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
import org.ramflux.feature.permissions.api.PermissionEntry

@OptIn(ExperimentalCoroutinesApi::class)
class PermissionsViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }
        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }
    private class Repository : OsPermissionsRepository {
        val asked = mutableSetOf<OsPermission>()
        val accounts = mutableSetOf<String>()
        var camera = OsPermissionStatus.NOT_ASKED
        var notifications = OsPermissionStatus.NOT_ASKED
        var fail = false
        var barrier: CompletableDeferred<Unit>? = null
        override suspend fun read(): OsPermissionResult<List<OsPermissionRow>> {
            barrier?.let { withContext(NonCancellable) { it.await() } }
            return OsPermissionResult.Ready(
                listOf(
                    OsPermissionRow(OsPermission.CAMERA, camera),
                    OsPermissionRow(OsPermission.NOTIFICATIONS, notifications),
                    OsPermissionRow(OsPermission.BATTERY, OsPermissionStatus.DENIED)
                )
            )
        }
        override suspend fun createChannels(labels: NotificationChannelLabels) = OsPermissionResult.Ready(Unit)
        override suspend fun claimPrompt(permission: OsPermission): OsPermissionResult<Boolean> =
            if (fail) OsPermissionResult.Failed else OsPermissionResult.Ready(asked.add(permission))
        override suspend fun claimCreatedAccountExplanation(accountId: String) = OsPermissionResult.Ready(accounts.add(accountId))
    }
    private fun TestScope.observe(vm: PermissionsViewModel, events: MutableList<PermissionsEvent>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect(events::add) }
    }

    @Test fun cameraExplainCancelNeverPromptsThenDeliberateContinueIsOneShotAndGrantRefreshIsAuthoritative() = runTest(
        timeout = 5.seconds
    ) {
        val repo = Repository()
        val vm = PermissionsViewModel(repo)
        val events = mutableListOf<PermissionsEvent>()
        observe(vm, events)
        vm.onAction(PermissionsAction.Open(PermissionEntry.CAMERA_SCAN, null))
        runCurrent()
        assertEquals(OsPermission.CAMERA, vm.state.value.explanation)
        assertTrue(events.isEmpty())
        vm.onAction(PermissionsAction.Cancel)
        runCurrent()
        assertTrue(repo.asked.isEmpty())
        assertTrue(events.isEmpty())
        val second = PermissionsViewModel(repo)
        val secondEvents = mutableListOf<PermissionsEvent>()
        observe(second, secondEvents)
        second.onAction(PermissionsAction.Open(PermissionEntry.CAMERA_SCAN, null))
        runCurrent()
        second.onAction(PermissionsAction.Continue)
        second.onAction(PermissionsAction.Continue)
        runCurrent()
        val request = secondEvents.single() as PermissionsEvent.Request
        assertTrue(second.consumeRequest(request))
        assertFalse(second.consumeRequest(request))
        repo.camera = OsPermissionStatus.ALLOWED
        second.onAction(PermissionsAction.SystemFinished)
        runCurrent()
        assertEquals(1, secondEvents.count { it == PermissionsEvent.Ready })
        second.onAction(PermissionsAction.Refresh)
        runCurrent()
        assertEquals(1, secondEvents.count { it == PermissionsEvent.Ready })
        repo.camera = OsPermissionStatus.DENIED
        second.onAction(PermissionsAction.Refresh)
        runCurrent()
        assertTrue(second.state.value.needed)
        assertNull(second.state.value.explanation)
    }

    @Test fun creationHookIsOnceAndSettingsLaunchNeverRequestsNotificationsOrMedia() = runTest(timeout = 5.seconds) {
        val repo = Repository()
        for (entry in listOf(PermissionEntry.SETTINGS, PermissionEntry.MICROPHONE, PermissionEntry.INCOMING_CALL)) {
            val vm = PermissionsViewModel(repo)
            val events = mutableListOf<PermissionsEvent>()
            observe(vm, events)
            vm.onAction(PermissionsAction.Open(entry, null))
            runCurrent()
            assertNull(vm.state.value.explanation)
            assertTrue(events.isEmpty())
            assertTrue(repo.asked.isEmpty())
        }
        repeat(2) { index ->
            val vm = PermissionsViewModel(repo)
            val events = mutableListOf<PermissionsEvent>()
            observe(vm, events)
            vm.onAction(PermissionsAction.Open(PermissionEntry.AFTER_ACCOUNT_CREATION, "new-account"))
            runCurrent()
            assertEquals(index == 0, vm.state.value.explanation == OsPermission.NOTIFICATIONS)
            vm.onAction(PermissionsAction.Cancel)
            runCurrent()
            assertTrue(events.isEmpty())
        }
    }

    @Test fun deniedFailedAndCancelledRoutesCannotLaunchQueuedPrompts() = runTest(timeout = 5.seconds) {
        val repo = Repository().apply { camera = OsPermissionStatus.DENIED }
        val vm = PermissionsViewModel(repo)
        val events = mutableListOf<PermissionsEvent>()
        observe(vm, events)
        vm.onAction(PermissionsAction.Open(PermissionEntry.CAMERA_PHOTO, null))
        runCurrent()
        assertTrue(vm.state.value.needed)
        vm.onAction(PermissionsAction.Continue)
        runCurrent()
        assertTrue(events.isEmpty())
        val other = Repository().apply { fail = true }
        val failed = PermissionsViewModel(other)
        observe(failed, mutableListOf())
        failed.onAction(PermissionsAction.Open(PermissionEntry.CAMERA_SCAN, null))
        runCurrent()
        failed.onAction(PermissionsAction.Continue)
        runCurrent()
        assertTrue(failed.state.value.failed)
        val live = PermissionsViewModel(Repository())
        val pending = mutableListOf<PermissionsEvent>()
        observe(live, pending)
        live.onAction(PermissionsAction.Open(PermissionEntry.CAMERA_SCAN, null))
        runCurrent()
        live.onAction(PermissionsAction.Continue)
        runCurrent()
        val request = pending.single() as PermissionsEvent.Request
        live.onAction(PermissionsAction.Back)
        runCurrent()
        assertFalse(live.consumeRequest(request))
    }

    @Test fun batteryIsSettingsOnlyAndLateReadsCannotReopenClosedRoute() = runTest(timeout = 5.seconds) {
        val repo = Repository()
        val vm = PermissionsViewModel(repo)
        val events = mutableListOf<PermissionsEvent>()
        observe(vm, events)
        vm.onAction(PermissionsAction.Open(PermissionEntry.SETTINGS, null))
        runCurrent()
        vm.onAction(PermissionsAction.Select(OsPermission.BATTERY))
        runCurrent()
        assertEquals(OsPermission.BATTERY, vm.state.value.explanation)
        vm.onAction(PermissionsAction.Cancel)
        runCurrent()
        assertTrue(events.isEmpty())
        vm.onAction(PermissionsAction.Select(OsPermission.BATTERY))
        vm.onAction(PermissionsAction.Continue)
        runCurrent()
        assertEquals(OsPermission.BATTERY, (events.single() as PermissionsEvent.Request).permission)
        val barrier = CompletableDeferred<Unit>()
        repo.barrier = barrier
        vm.onAction(PermissionsAction.Refresh)
        runCurrent()
        vm.onAction(PermissionsAction.Back)
        runCurrent()
        barrier.complete(Unit)
        runCurrent()
        assertTrue(vm.state.value.rows.isEmpty())
        assertNull(vm.state.value.explanation)
    }
}
