// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.data.BackupJob
import org.ramflux.data.BackupJobKind
import org.ramflux.data.BackupJobState
import org.ramflux.data.BackupRepository
import org.ramflux.designsystem.*
import org.ramflux.designsystem.kit.*
import org.ramflux.feature.onboarding.impl.R

/** Host owns navigation; archives move through the Storage Access Framework only (create and open document). */
@Composable
fun BackupNavigation(freshInstall: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier, vm: BackupViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    var saving by remember { mutableStateOf<String?>(null) }
    // A pick or a save location that comes back into a locked account is refused, as a cancelled picker is.
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
        rememberAppLockCheckedResult(
            onRefused = { vm.onAction(BackupAction.Picked(null)) }
        ) { uri: Uri? -> vm.onAction(BackupAction.Picked(uri)) }
    )
    val exported = { uri: Uri? ->
        saving?.let { vm.onAction(BackupAction.Exported(it, uri)) }
        saving = null
    }
    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
        rememberAppLockCheckedResult(onRefused = { exported(null) }, onResult = exported)
    )
    LaunchedEffect(freshInstall) { vm.onAction(BackupAction.Open(freshInstall)) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onAction(BackupAction.Visible(true)) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onAction(BackupAction.Visible(false)) }
    BackHandler { vm.onAction(BackupAction.Back) }
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                // Archives have no registered type; providers report them as generic binary files.
                BackupEvent.PickArchive -> pick.launch(arrayOf("application/octet-stream"))
                is BackupEvent.SaveArchive -> {
                    saving = event.job
                    save.launch(event.suggestedName)
                }
                BackupEvent.Back -> back()
            }
        }
    }
    BackupScreen(state, vm::onAction, modifier)
}

@Composable
fun BackupScreen(state: BackupState, onAction: (BackupAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    val passphrase = remember { ErasableBuffer() }
    val code = remember { ErasableBuffer() }
    var filled by remember { mutableIntStateOf(0) }
    Scaffold(modifier = modifier.testTag("backup-window"), contentWindowInsets = WindowInsets(0, 0, 0, 0)) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            KitPageHeader(stringResource(if (state.freshInstall) R.string.backup_restore_title else R.string.backup_title), onBack = {
                onAction(BackupAction.Back)
            })
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = theme.gap.dp),
                verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
            ) {
                state.problem?.let { problem -> item("problem") { Text(problem.render(), Modifier.testTag("backup-problem")) } }
                when (state.step) {
                    BackupStep.LIST, BackupStep.PICKING -> {
                        item("actions") {
                            Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                                if (!state.freshInstall) {
                                    KitButton(stringResource(R.string.backup_create), {
                                        onAction(BackupAction.StartCreate)
                                    }, enabled = state.ready && !state.working, tag = "backup-create")
                                }
                                KitButton(
                                    stringResource(R.string.backup_restore),
                                    { onAction(BackupAction.StartRestore) },
                                    kind = KitButtonKind.Secondary,
                                    enabled = state.ready && !state.working,
                                    tag = "backup-restore"
                                )
                                Text(stringResource(R.string.backup_storage_help), color = theme.secondary)
                            }
                        }
                        if (state.ready && !state.loading && state.jobs.isEmpty()) {
                            item("empty") { KitEmpty("database-backup", stringResource(R.string.backup_empty), "") }
                        }
                        items(state.jobs, key = { "job:" + it.id }, contentType = { "job" }) { job -> JobRow(job, onAction) }
                    }
                    BackupStep.CREATE -> item("create") {
                        Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                            Text(stringResource(R.string.backup_passphrase_help))
                            ErasableSecretField(
                                passphrase,
                                stringResource(R.string.backup_passphrase),
                                "backup-passphrase",
                                !state.working,
                                true,
                                1024
                            ) {
                                filled = passphrase.owned().size
                            }
                            KitButton(
                                stringResource(R.string.backup_create_confirm),
                                { onAction(BackupAction.SubmitCreate(passphrase)) },
                                enabled = !state.working && filled >= BackupRepository.MIN_ARCHIVE_PASSPHRASE,
                                tag = "backup-create-confirm"
                            )
                        }
                    }
                    BackupStep.IMPORTING, BackupStep.VERIFYING -> item(
                        "working"
                    ) { KitEmpty("loader", stringResource(R.string.backup_working), "") }
                    BackupStep.RESTORE_PASSPHRASE -> item("verify") {
                        Column(verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                            Text(stringResource(R.string.backup_verify_help))
                            ErasableSecretField(
                                passphrase,
                                stringResource(R.string.backup_passphrase),
                                "restore-passphrase",
                                !state.working,
                                true,
                                1024
                            ) {
                                filled = passphrase.owned().size
                            }
                            KitButton(stringResource(R.string.backup_verify), {
                                onAction(BackupAction.SubmitVerify(passphrase))
                            }, enabled = !state.working && filled > 0, tag = "backup-verify")
                        }
                    }
                    BackupStep.INSTALL -> state.verified?.let { job -> item("install") { Install(job, state, code, onAction) } }
                }
            }
        }
    }
}

@Composable
private fun Install(job: BackupJob, state: BackupState, code: ErasableBuffer, onAction: (BackupAction) -> Unit) {
    val theme = LocalRFTheme.current
    val archive = job.archive ?: return
    var digits by remember { mutableIntStateOf(0) }
    Column(Modifier.testTag("backup-install"), verticalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
        KitSummaryCard(archive.label ?: archive.account, stringResource(R.string.backup_archive_account))
        LtrCode(archive.account, tag = "backup-archive-account")
        archive.createdAt?.let { Text(stringResource(R.string.backup_archive_created, UiText.DateTime(it).render())) }
        if (archive.merge) {
            Text(stringResource(R.string.backup_merge_help))
            if (state.confirmMerge) {
                KitSecurityWarning(
                    stringResource(R.string.backup_merge_confirm_title),
                    stringResource(R.string.backup_merge_confirm_body),
                    KitWarningTone.Warning
                )
                KitButton(stringResource(R.string.backup_merge_confirm), {
                    onAction(BackupAction.ConfirmMerge)
                }, enabled = !state.working, tag = "backup-merge-confirm")
            } else {
                KitButton(
                    stringResource(R.string.backup_merge),
                    { onAction(BackupAction.Merge) },
                    enabled = !state.working,
                    tag = "backup-merge"
                )
            }
        } else {
            Text(stringResource(R.string.backup_new_account_help))
            AccountCodeField(
                code,
                stringResource(R.string.backup_new_code),
                "backup-new-code",
                !state.working
            ) { digits = code.owned().size }
            KitButton(stringResource(R.string.backup_install_new), {
                onAction(BackupAction.InstallNew(code))
            }, enabled = !state.working && digits == 6, tag = "backup-install-new")
        }
    }
}

@Composable
private fun JobRow(job: BackupJob, onAction: (BackupAction) -> Unit) {
    val theme = LocalRFTheme.current
    val title = stringResource(if (job.kind == BackupJobKind.BACKUP) R.string.backup_job_backup else R.string.backup_job_restore)
    val status = stringResource(
        when (job.state) {
            BackupJobState.PREPARING, BackupJobState.WRITING -> R.string.backup_state_writing
            BackupJobState.VERIFYING -> R.string.backup_state_verifying
            BackupJobState.VERIFIED -> R.string.backup_state_verified
            BackupJobState.INSTALLING -> R.string.backup_state_installing
            BackupJobState.DONE -> R.string.backup_state_done
            BackupJobState.FAILED -> R.string.backup_state_failed
            BackupJobState.CANCELLED -> R.string.backup_state_cancelled
        }
    )
    Column(Modifier.fillMaxWidth().testTag("job:" + job.id), verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
        KitRow(title, {}, value = status, trailing = KitRowTrailing.None, icon = "database-backup")
        if (!job.state.terminal) {
            val total = job.bytesTotal ?: job.itemsTotal
            val done = if (job.bytesTotal != null) job.bytesDone else job.itemsDone
            Text(
                if (total != null && total > 0) {
                    stringResource(R.string.backup_progress, (done * 100 / total).toInt(), job.category)
                } else {
                    stringResource(R.string.backup_progress_open, job.category)
                },
                color = theme.secondary
            )
        }
        Text(UiText.DateTime(job.createdAt).render(), color = theme.secondary)
        job.failure?.let { LtrCode(it, tag = "job-failure") }
        Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!job.state.terminal && job.state != BackupJobState.INSTALLING) {
                KitButton(stringResource(R.string.backup_cancel), {
                    onAction(BackupAction.Cancel(job.id))
                }, kind = KitButtonKind.Secondary, size = KitButtonSize.Compact, tag = "job-cancel")
            }
            if (job.kind == BackupJobKind.BACKUP && job.state == BackupJobState.DONE) {
                KitButton(
                    stringResource(R.string.backup_save),
                    { onAction(BackupAction.Export(job.id)) },
                    size = KitButtonSize.Compact,
                    tag = "job-save"
                )
            }
        }
    }
}
