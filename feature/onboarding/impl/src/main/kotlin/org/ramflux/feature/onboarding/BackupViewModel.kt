// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.onboarding.impl.R

enum class BackupStep { LIST, CREATE, PICKING, IMPORTING, RESTORE_PASSPHRASE, VERIFYING, INSTALL }

@Immutable data class BackupState(
    val freshInstall: Boolean = false,
    /** Null on a fresh install, or before the active account is known. */
    val account: String? = null,
    val ready: Boolean = false,
    val loading: Boolean = false,
    val jobs: ImmutableList<BackupJob> = persistentListOf(),
    val step: BackupStep = BackupStep.LIST,
    /** The verified restore waiting for install; [BackupArchive.merge] picks merge or new account. */
    val verified: BackupJob? = null,
    val confirmMerge: Boolean = false,
    val working: Boolean = false,
    val problem: UiText? = null
)

sealed interface BackupAction {
    data class Open(val freshInstall: Boolean) : BackupAction
    data class Visible(val visible: Boolean) : BackupAction
    data object StartCreate : BackupAction
    class SubmitCreate(val passphrase: ErasableBuffer) : BackupAction
    data object StartRestore : BackupAction
    data class Picked(val source: Uri?) : BackupAction
    class SubmitVerify(val passphrase: ErasableBuffer) : BackupAction
    data object Merge : BackupAction
    data object ConfirmMerge : BackupAction
    class InstallNew(val code: ErasableBuffer) : BackupAction
    data class Cancel(val job: String) : BackupAction
    data class Export(val job: String) : BackupAction
    data class Exported(val job: String, val destination: Uri?) : BackupAction
    data object Back : BackupAction
}

sealed interface BackupEvent {
    data object PickArchive : BackupEvent
    data class SaveArchive(val job: String, val suggestedName: String) : BackupEvent
    data object Back : BackupEvent
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backups: BackupRepository,
    private val accounts: AccountAccessRepository
) : ViewModel() {
    private val local = MutableStateFlow(BackupState())
    val state = local.asStateFlow()
    private val channel = Channel<BackupEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var opened = false
    private var watch: Job? = null
    private var work: Job? = null

    /** The private copy of a picked archive until its verify job exists. */
    private var imported: String? = null

    fun onAction(action: BackupAction) {
        when (action) {
            is BackupAction.Open -> if (!opened) {
                opened = true
                local.update { it.copy(freshInstall = action.freshInstall, step = if (action.freshInstall) BackupStep.LIST else it.step) }
                viewModelScope.launch { resolve(action.freshInstall) }
            }
            is BackupAction.Visible -> if (action.visible) {
                watch()
            } else {
                watch?.cancel()
                watch = null
            }
            BackupAction.StartCreate -> if (!local.value.freshInstall && local.value.account != null) step(BackupStep.CREATE)
            is BackupAction.SubmitCreate -> {
                val account = local.value.account
                val secret = action.passphrase.snapshot().also { action.passphrase.clear() }
                if (account == null || busy()) {
                    secret.fill('\u0000')
                    return
                }
                run { reply(backups.create(account, secret)) { step(BackupStep.LIST) } }
            }
            BackupAction.StartRestore -> if (!busy()) {
                step(BackupStep.PICKING)
                event(BackupEvent.PickArchive)
            }
            is BackupAction.Picked -> {
                val source = action.source
                if (source == null) {
                    step(BackupStep.LIST)
                    return
                }
                step(BackupStep.IMPORTING)
                run {
                    when (val copied = backups.import(source)) {
                        is ClientResult.Ready -> {
                            imported = copied.value
                            step(BackupStep.RESTORE_PASSPHRASE)
                        }
                        is ClientResult.Failed -> local.update { it.copy(step = BackupStep.LIST, problem = copied.problem.backupMessage()) }
                        is ClientResult.Unavailable -> local.update {
                            it.copy(
                                step = BackupStep.LIST,
                                problem = uiText(R.string.backup_unavailable)
                            )
                        }
                    }
                }
            }
            is BackupAction.SubmitVerify -> {
                val archive = imported
                val secret = action.passphrase.snapshot().also { action.passphrase.clear() }
                if (archive == null || busy()) {
                    secret.fill('\u0000')
                    return
                }
                imported = null
                step(BackupStep.VERIFYING)
                run {
                    reply(backups.verify(local.value.account, archive, secret)) { job ->
                        if (job.state == BackupJobState.VERIFIED) {
                            local.update { it.copy(step = BackupStep.INSTALL, verified = job) }
                        } else {
                            local.update { it.copy(step = BackupStep.LIST) }
                        }
                    }
                    if (local.value.step == BackupStep.VERIFYING) local.update { it.copy(step = BackupStep.LIST) }
                }
            }
            BackupAction.Merge -> if (local.value.verified?.archive?.merge == true) local.update { it.copy(confirmMerge = true) }
            BackupAction.ConfirmMerge -> {
                val job = local.value.verified ?: return
                val archive = job.archive ?: return
                if (!local.value.confirmMerge || !archive.merge || busy()) return
                local.update { it.copy(confirmMerge = false) }
                // Merging writes into the existing account named by the archive; the SDK checks it is unlocked here.
                run {
                    reply(
                        backups.install(archive.account, job.id, null)
                    ) { local.update { it.copy(step = BackupStep.LIST, verified = null) } }
                }
            }
            is BackupAction.InstallNew -> {
                val job = local.value.verified
                val code = action.code.snapshot().also { action.code.clear() }
                if (job == null || job.archive?.merge != false || busy()) {
                    code.fill('\u0000')
                    return
                }
                run { reply(backups.install(null, job.id, code)) { local.update { it.copy(step = BackupStep.LIST, verified = null) } } }
            }
            is BackupAction.Cancel -> if (!busy()) run { reply(backups.cancel(local.value.account, action.job)) {} }
            is BackupAction.Export -> local.value.jobs.firstOrNull {
                it.id == action.job && it.kind == BackupJobKind.BACKUP && it.state == BackupJobState.DONE
            }?.let {
                event(BackupEvent.SaveArchive(it.id, "ramflux-backup-" + it.createdAt + ".rfbackup"))
            }
            is BackupAction.Exported -> {
                val destination = action.destination ?: return
                run {
                    when (val saved = backups.export(action.job, destination)) {
                        is ClientResult.Ready -> local.update { it.copy(problem = uiText(R.string.backup_saved)) }
                        is ClientResult.Failed -> local.update { it.copy(problem = saved.problem.backupMessage()) }
                        is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.backup_unavailable)) }
                    }
                }
            }
            BackupAction.Back -> when (local.value.step) {
                BackupStep.LIST -> {
                    discardImport()
                    event(BackupEvent.Back)
                }
                BackupStep.INSTALL -> {
                    val job = local.value.verified
                    local.update { it.copy(step = BackupStep.LIST, verified = null, confirmMerge = false) }
                    if (job != null) {
                        viewModelScope.launch {
                            backups.cancel(local.value.account, job.id)
                            backups.discard(job.id)
                        }
                    }
                }
                else -> if (!busy()) {
                    discardImport()
                    step(BackupStep.LIST)
                }
            }
        }
    }

    private suspend fun resolve(freshInstall: Boolean) {
        if (freshInstall) {
            local.update { it.copy(account = null, ready = true) }
        } else {
            when (val listed = accounts.list()) {
                is ClientResult.Ready -> {
                    val active = listed.value.accounts.find { it.id == listed.value.activeId && it.unlocked }?.id
                    local.update {
                        it.copy(
                            account = active,
                            ready = active != null,
                            problem = if (active == null) uiText(R.string.backup_account_needed) else null
                        )
                    }
                }
                is ClientResult.Failed -> local.update { it.copy(problem = listed.problem.backupMessage()) }
                is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.backup_unavailable)) }
            }
        }
        if (local.value.ready) {
            reload()
            if (watch == null) watch()
        }
    }

    /** Follows the backup-job stream while the screen is shown; each change rereads the bounded job list. */
    private fun watch() {
        if (!local.value.ready || watch?.isActive == true) return
        val account = local.value.account
        watch = viewModelScope.launch {
            backups.changes(account).collect { change ->
                if (change is ClientResult.Ready) {
                    reload()
                } else if (change is ClientResult.Failed) local.update { it.copy(problem = change.problem.backupMessage()) }
            }
        }
    }

    private suspend fun reload() {
        local.update { it.copy(loading = true) }
        when (val rows = backups.jobs(local.value.account)) {
            is ClientResult.Ready -> {
                // A restore archive copy is removed as soon as its job ends.
                rows.value.filter { it.kind == BackupJobKind.RESTORE && it.state.terminal }.forEach { backups.discard(it.id) }
                rows.value.filter {
                    it.kind == BackupJobKind.BACKUP && it.state.terminal && it.state != BackupJobState.DONE
                }.forEach { backups.discard(it.id) }
                local.update { it.copy(loading = false, jobs = rows.value.toPersistentList()) }
            }
            is ClientResult.Failed -> local.update { it.copy(loading = false, problem = rows.problem.backupMessage()) }
            is ClientResult.Unavailable -> local.update { it.copy(loading = false, problem = uiText(R.string.backup_unavailable)) }
        }
    }

    private inline fun reply(result: ClientResult<BackupJob>, onReady: (BackupJob) -> Unit) {
        when (result) {
            is ClientResult.Ready -> {
                onReady(result.value)
                local.update { it.copy(problem = null) }
            }
            is ClientResult.Failed -> local.update { it.copy(problem = result.problem.backupMessage()) }
            is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.backup_unavailable)) }
        }
    }

    private fun run(block: suspend () -> Unit) {
        work = viewModelScope.launch {
            local.update { it.copy(working = true, problem = null) }
            try {
                block()
                reload()
            } finally {
                local.update { it.copy(working = false) }
            }
        }
    }
    private fun busy() = work?.isActive == true
    private fun step(step: BackupStep) = local.update { it.copy(step = step, problem = null) }
    private fun event(event: BackupEvent) {
        viewModelScope.launch { channel.send(event) }
    }
    private fun discardImport() {
        val path = imported ?: return
        imported = null
        viewModelScope.launch { backups.discard(path) }
    }

    override fun onCleared() {
        // The screen is gone: an imported archive that never reached verify is removed now.
        imported?.let { path -> kotlinx.coroutines.runBlocking { backups.discard(path) } }
        imported = null
        super.onCleared()
    }
}

private fun ClientProblem.backupMessage(): UiText = uiText(
    when (statusName) {
        WEAK_PASSPHRASE, "weak_passphrase", "passphrase_too_short", "common_passphrase" -> R.string.backup_weak_passphrase
        "wrong_passphrase" -> R.string.backup_wrong_passphrase
        TOO_LARGE -> R.string.backup_too_large
        "not_six_digits", "trivial_code" -> R.string.backup_code_refused
        "account_locked" -> R.string.backup_account_locked
        else -> if (outcome == ClientOutcome.UNKNOWN) R.string.backup_unknown else R.string.backup_failed
    }
)
