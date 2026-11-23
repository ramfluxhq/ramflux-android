// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.ramflux.sdk.*

internal class DefaultBackupRepository(
    transport: RepositoryTransport,
    private val files: BackupFileStore,
    private val known: KnownAccountRegistry = MemoryKnownAccountRegistry()
) : BackupRepository {
    private val accounts = DefaultAccountsRepository(transport)
    private val events = transport

    /** Private archive path per job id, so export and discard never take a path from the screen. */
    private val paths = HashMap<String, String>()

    override suspend fun jobs(account: String?): ClientResult<List<BackupJob>> {
        if (!valid(account)) return invalid()
        return when (val reply = accounts.accountBackupJobs(context(account), SecretBody.fields {})) {
            is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
            is CoreResult.Success -> when (val rows = BackupMetadataReader.jobs(reply.value)) {
                is CoreResult.Failure -> ClientResult.Failed(rows.error.appProblem())
                is CoreResult.Success -> {
                    val jobs = rows.value.map { remember(it) }.sortedByDescending { it.createdAt }
                    jobs.forEach { recordInstalled(it) }
                    ClientResult.Ready(jobs)
                }
            }
        }
    }

    override fun changes(account: String?): Flow<ClientResult<Unit>> = flow {
        if (!valid(account)) {
            emit(invalid())
            return@flow
        }
        val filter = SubscriptionFilter(account, buildJsonObject { put("topics", JsonArray(listOf(JsonPrimitive(BACKUP_JOB_TOPIC)))) })
        events.updates(filter).collect { update ->
            when (update) {
                is CoreUpdate.Events -> {
                    update.body.close()
                    emit(ClientResult.Ready(Unit))
                }
                is CoreUpdate.Failure -> emit(ClientResult.Failed(update.error.appProblem()))
                is CoreUpdate.Terminal -> emit(
                    if (update.kind == "resync_required") ClientResult.Ready(Unit)
                    else ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
                )
            }
        }
    }

    override suspend fun create(account: String, passphrase: CharArray): ClientResult<BackupJob> {
        try {
            if (!valid(account) || passphrase.size < BackupRepository.MIN_ARCHIVE_PASSPHRASE) return refusedPassphrase()
            val path = files.newArchivePath()
            val body = SecretBody.fields { publicText("output_path", path); chars("passphrase", passphrase) }
            val result = jobOf(accounts.accountBackupCreate(context(account), body))
            if (result !is ClientResult.Ready) files.delete(path)
            return result
        } finally { passphrase.fill('\u0000') }
    }

    override suspend fun export(job: String, destination: Uri): ClientResult<Unit> {
        val path = synchronized(paths) { paths[job] } ?: return invalid()
        if (!files.copyOut(path, destination)) return ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        discard(job)
        return ClientResult.Ready(Unit)
    }

    override suspend fun import(source: Uri): ClientResult<String> = when (val copy = files.copyIn(source, BackupRepository.MAX_IMPORT_BYTES)) {
        is ImportCopy.Copied -> ClientResult.Ready(copy.path)
        ImportCopy.TooLarge -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, statusName = TOO_LARGE))
        ImportCopy.Failed -> ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
    }

    override suspend fun verify(account: String?, archive: String, passphrase: CharArray): ClientResult<BackupJob> {
        try {
            if (!valid(account) || archive.isBlank()) return invalid()
            val body = SecretBody.fields { publicText("input_path", archive); chars("passphrase", passphrase) }
            val result = jobOf(accounts.accountRestoreVerify(context(account), body))
            if (result !is ClientResult.Ready) files.delete(archive)
            return result
        } finally { passphrase.fill('\u0000') }
    }

    override suspend fun install(account: String?, job: String, newCode: CharArray?): ClientResult<BackupJob> {
        try {
            if (!valid(account) || job.isBlank()) return invalid()
            if (newCode != null) AccountCode.newCodeRefusal(newCode)?.let {
                return ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = it.name.lowercase()))
            }
            // The SDK field keeps its historical name; for a new account its value is the six-digit account code.
            val body = SecretBody.fields {
                publicText("job_id", job)
                if (newCode != null) chars("passphrase", newCode)
            }
            return jobOf(accounts.accountRestoreInstall(context(account), body)).also { result ->
                if (result is ClientResult.Ready) recordInstalled(result.value)
            }
        } finally { newCode?.fill('\u0000') }
    }

    override suspend fun cancel(account: String?, job: String): ClientResult<BackupJob> {
        if (!valid(account) || job.isBlank()) return invalid()
        return jobOf(accounts.accountBackupCancel(context(account), SecretBody.fields { publicText("job_id", job) }))
    }

    override suspend fun discard(job: String) {
        val path = synchronized(paths) { paths.remove(job) } ?: job.takeIf { files.owns(it) } ?: return
        files.delete(path)
    }

    private fun jobOf(reply: CoreResult<SecretReply>): ClientResult<BackupJob> = when (reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.appProblem())
        is CoreResult.Success -> when (val row = BackupMetadataReader.job(reply.value)) {
            is CoreResult.Failure -> ClientResult.Failed(row.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(remember(row.value))
        }
    }

    /** A finished restore that installed an account adds it to this device's registry (public facts only). */
    private suspend fun recordInstalled(job: BackupJob) {
        val installed = job.installedAccount?.takeIf { job.kind == BackupJobKind.RESTORE && job.state == BackupJobState.DONE } ?: return
        val name = job.archive?.takeIf { it.account == installed }?.label.orEmpty()
        withContext(Dispatchers.IO) { known.record(KnownAccount(installed, name)) }
    }

    private fun remember(row: BackupJobDisplay): BackupJob {
        if (files.owns(row.path)) synchronized(paths) { paths[row.id] = row.path }
        return BackupJob(
            row.id, if (row.kind == "backup") BackupJobKind.BACKUP else BackupJobKind.RESTORE,
            BackupJobState.valueOf(row.state.uppercase()), row.createdAt, row.finishedAt, row.category,
            row.itemsDone, row.itemsTotal, row.bytesDone, row.bytesTotal,
            row.archiveAccount?.let { BackupArchive(it, row.archiveLabel, row.archiveCreatedAt, row.merge == true) },
            row.installedAccount, row.failure, row.retryable,
        )
    }

    private fun context(account: String?) = RepositoryContext(account, UUID.randomUUID().toString())
    private fun valid(account: String?) = account == null || (account.isNotBlank() && account.length <= 128)
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun refusedPassphrase() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE, statusName = WEAK_PASSPHRASE))
}

/** The SDK's backup-job stream topic (`ramflux-sdk` `backup/model.rs` `BACKUP_JOB_TOPIC`). */
internal const val BACKUP_JOB_TOPIC = "account.backup.job.changed"

/** Client-side refusal names, alongside the SDK's own (`wrong_passphrase`, ...). */
const val TOO_LARGE = "too_large"
const val WEAK_PASSPHRASE = "weak_passphrase"
