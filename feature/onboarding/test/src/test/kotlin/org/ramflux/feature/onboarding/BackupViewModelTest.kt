// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.net.Uri
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupViewModelTest {
    @get:Rule val main = object : TestWatcher() {
        override fun starting(description: Description) = Dispatchers.setMain(StandardTestDispatcher())
        override fun finished(description: Description) = Dispatchers.resetMain()
    }

    private class Backups : BackupRepository {
        val calls = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val changes = MutableSharedFlow<ClientResult<Unit>>()
        var listed = listOf<BackupJob>()
        var importResult: ClientResult<String> = ClientResult.Ready("/private/backups/in.rfbackup")
        var verified: BackupJob? = null
        var secrets = mutableListOf<String>()
        override suspend fun jobs(account: String?) = ClientResult.Ready(listed).also { calls += "jobs:$account" }
        override fun changes(account: String?): Flow<ClientResult<Unit>> = changes.onStart { calls += "watch:$account" }
        override suspend fun create(account: String, passphrase: CharArray): ClientResult<BackupJob> {
            secrets += String(passphrase); passphrase.fill('\u0000'); calls += "create:$account"
            return ClientResult.Ready(job("b1", BackupJobKind.BACKUP, BackupJobState.PREPARING))
        }
        override suspend fun export(job: String, destination: Uri) = ClientResult.Ready(Unit).also { calls += "export:$job" }
        override suspend fun import(source: Uri) = importResult.also { calls += "import" }
        override suspend fun verify(account: String?, archive: String, passphrase: CharArray): ClientResult<BackupJob> {
            secrets += String(passphrase); passphrase.fill('\u0000'); calls += "verify:$account:$archive"
            return ClientResult.Ready(requireNotNull(verified))
        }
        override suspend fun install(account: String?, job: String, newCode: CharArray?): ClientResult<BackupJob> {
            calls += "install:$account:$job:${newCode?.let(::String)}"
            newCode?.fill('\u0000')
            return ClientResult.Ready(job(job, BackupJobKind.RESTORE, BackupJobState.INSTALLING))
        }
        override suspend fun cancel(account: String?, job: String) = ClientResult.Ready(job(job, BackupJobKind.RESTORE, BackupJobState.CANCELLED)).also { calls += "cancel:$job" }
        override suspend fun discard(job: String) { discarded += job }
    }
    private class Accounts(private val active: String?) : AccountAccessRepository {
        override suspend fun list() = ClientResult.Ready(AccountCatalog(listOfNotNull(active?.let { AccountSummary(it, "Work", true, "unlocked") }), active))
        override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
        override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    }
    companion object {
        fun job(id: String, kind: BackupJobKind, state: BackupJobState, archive: BackupArchive? = null) =
            BackupJob(id, kind, state, 1_700_000_000, null, "messages", 1, 4, 10, 100, archive, null, null, false)
    }
    private fun buffer(text: String) = ErasableBuffer().apply { replace(text.toCharArray()) }

    @Test fun settingsCreatesABackupFromAnErasableBufferAndSavesItThroughTheSystemPicker() = runTest(timeout = 5.seconds) {
        val backups = Backups().apply { listed = listOf(job("done", BackupJobKind.BACKUP, BackupJobState.DONE)) }
        val vm = BackupViewModel(backups, Accounts("account"))
        val events = mutableListOf<BackupEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        vm.onAction(BackupAction.Open(false))
        runCurrent()
        assertEquals("account", vm.state.value.account)
        assertTrue("subscribes to the job stream on appear", "watch:account" in backups.calls)
        vm.onAction(BackupAction.StartCreate)
        val passphrase = buffer("a long archive passphrase")
        vm.onAction(BackupAction.SubmitCreate(passphrase))
        runCurrent()
        assertTrue(passphrase.isEmpty())
        assertEquals(listOf("a long archive passphrase"), backups.secrets)
        assertEquals(BackupStep.LIST, vm.state.value.step)
        vm.onAction(BackupAction.Export("done"))
        runCurrent()
        val save = events.filterIsInstance<BackupEvent.SaveArchive>().single()
        vm.onAction(BackupAction.Exported(save.job, Uri.parse("content://documents/1")))
        runCurrent()
        assertEquals("export:done", backups.calls.last { it.startsWith("export") })

        backups.changes.emit(ClientResult.Ready(Unit))
        runCurrent()
        assertTrue(backups.calls.count { it.startsWith("jobs") } >= 3)
        vm.onAction(BackupAction.Visible(false))
        runCurrent()
        assertEquals("unsubscribes on disappear", 0, backups.changes.subscriptionCount.value)
    }

    @Test fun freshInstallRestoresAsANewAccountAfterImportAndVerify() = runTest(timeout = 5.seconds) {
        val backups = Backups().apply {
            verified = job("r1", BackupJobKind.RESTORE, BackupJobState.VERIFIED, BackupArchive("acct", "Lin", 1_700_000_000, merge = false))
        }
        val vm = BackupViewModel(backups, Accounts(null))
        val events = mutableListOf<BackupEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.toList(events) }
        vm.onAction(BackupAction.Open(true))
        runCurrent()
        assertNull(vm.state.value.account)
        vm.onAction(BackupAction.StartCreate)
        assertEquals("no backup without an account", BackupStep.LIST, vm.state.value.step)
        vm.onAction(BackupAction.StartRestore)
        runCurrent()
        assertEquals(BackupEvent.PickArchive, events.single())
        vm.onAction(BackupAction.Picked(Uri.parse("content://documents/archive")))
        runCurrent()
        assertEquals(BackupStep.RESTORE_PASSPHRASE, vm.state.value.step)
        vm.onAction(BackupAction.SubmitVerify(buffer("a long archive passphrase")))
        runCurrent()
        assertEquals("verify:null:/private/backups/in.rfbackup", backups.calls.first { it.startsWith("verify") })
        assertEquals(BackupStep.INSTALL, vm.state.value.step)
        vm.onAction(BackupAction.Merge)
        assertFalse("a new account is never merged", vm.state.value.confirmMerge)
        val code = buffer("258036")
        vm.onAction(BackupAction.InstallNew(code))
        runCurrent()
        assertTrue(code.isEmpty())
        assertEquals("install:null:r1:258036", backups.calls.first { it.startsWith("install") })
        assertEquals(BackupStep.LIST, vm.state.value.step)
    }

    @Test fun mergeNeedsTheHostConfirmationAndLeavingDiscardsAnUnverifiedImport() = runTest(timeout = 5.seconds) {
        val backups = Backups().apply {
            verified = job("r2", BackupJobKind.RESTORE, BackupJobState.VERIFIED, BackupArchive("account", null, null, merge = true))
        }
        val vm = BackupViewModel(backups, Accounts("account"))
        vm.onAction(BackupAction.Open(false))
        runCurrent()
        vm.onAction(BackupAction.Picked(Uri.parse("content://documents/archive")))
        runCurrent()
        vm.onAction(BackupAction.Back)
        runCurrent()
        assertEquals(listOf("/private/backups/in.rfbackup"), backups.discarded)
        assertEquals(BackupStep.LIST, vm.state.value.step)

        vm.onAction(BackupAction.Picked(Uri.parse("content://documents/archive")))
        runCurrent()
        vm.onAction(BackupAction.SubmitVerify(buffer("a long archive passphrase")))
        runCurrent()
        vm.onAction(BackupAction.ConfirmMerge)
        runCurrent()
        assertFalse(backups.calls.any { it.startsWith("install") })
        vm.onAction(BackupAction.Merge)
        vm.onAction(BackupAction.ConfirmMerge)
        runCurrent()
        assertEquals("install:account:r2:null", backups.calls.first { it.startsWith("install") })

        backups.importResult = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = TOO_LARGE))
        vm.onAction(BackupAction.Picked(Uri.parse("content://documents/huge")))
        runCurrent()
        assertEquals(BackupStep.LIST, vm.state.value.step)
        assertNotNull(vm.state.value.problem)
    }
}
