// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.net.Uri
import kotlinx.coroutines.flow.Flow

enum class BackupJobKind { BACKUP, RESTORE }

enum class BackupJobState(val terminal: Boolean = false) {
    PREPARING, WRITING, VERIFYING, VERIFIED, INSTALLING, DONE(true), FAILED(true), CANCELLED(true)
}

/** What a verified archive holds, before it is installed. [merge] is true when its account exists here. */
data class BackupArchive(val account: String, val label: String?, val createdAt: Long?, val merge: Boolean)

data class BackupJob(
    val id: String,
    val kind: BackupJobKind,
    val state: BackupJobState,
    val createdAt: Long,
    val finishedAt: Long?,
    /** The SDK's progress category (`identity`, `messages`, ...), shown as the current step. */
    val category: String,
    val itemsDone: Long,
    val itemsTotal: Long?,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val archive: BackupArchive?,
    val installedAccount: String?,
    /** The SDK's failure code (`wrong_passphrase`, `insufficient_space`, ...). */
    val failure: String?,
    val retryable: Boolean,
)

/**
 * Backup and restore on this phone (`account.backup.create/jobs/cancel`, `account.restore.verify/install`).
 *
 * Archives exist only in app-private no-backup storage: a new backup is written there and handed to the
 * user with [export] (Storage Access Framework, create document), after which the private file is removed;
 * an archive to restore is copied in with [import] under [MAX_IMPORT_BYTES], checked before and during the
 * copy, and removed by [discard] when its job ends. [account] is null on a fresh install (no account yet):
 * jobs then follow the SDK's account-less backup-job stream.
 */
interface BackupRepository {
    suspend fun jobs(account: String?): ClientResult<List<BackupJob>>

    /** Emits on every job change; the caller rereads [jobs]. */
    fun changes(account: String?): Flow<ClientResult<Unit>>

    /** The archive passphrase is the archive's own secret (at least [MIN_ARCHIVE_PASSPHRASE] characters); wiped before return. */
    suspend fun create(account: String, passphrase: CharArray): ClientResult<BackupJob>

    /** Copies the finished archive of [job] to [destination] and removes the private copy. */
    suspend fun export(job: String, destination: Uri): ClientResult<Unit>

    /** Copies [source] into private storage; returns its private handle for [verify]. */
    suspend fun import(source: Uri): ClientResult<String>
    suspend fun verify(account: String?, archive: String, passphrase: CharArray): ClientResult<BackupJob>

    /**
     * Installs a verified restore. Merging into an existing account needs no code; restoring as a new
     * account sets its six-digit [newCode] (wiped before return).
     */
    suspend fun install(account: String?, job: String, newCode: CharArray?): ClientResult<BackupJob>
    suspend fun cancel(account: String?, job: String): ClientResult<BackupJob>

    /** Removes the private archive of a finished job (or an import that was never verified). */
    suspend fun discard(job: String)

    companion object {
        const val MAX_IMPORT_BYTES = 256L * 1024 * 1024
        const val MIN_ARCHIVE_PASSPHRASE = 15
    }
}

/** Private archive files; the Android implementation uses `noBackupFilesDir` and the content resolver. */
interface BackupFileStore {
    /** A new private path for the SDK to write a backup to. */
    fun newArchivePath(): String
    suspend fun copyOut(path: String, destination: Uri): Boolean

    /** Copies [source] to a new private path, refusing more than [limit] bytes before and during the copy. */
    suspend fun copyIn(source: Uri, limit: Long): ImportCopy
    fun delete(path: String)

    /** True only for a path inside this store's private directory. */
    fun owns(path: String): Boolean
}

sealed interface ImportCopy {
    data class Copied(val path: String) : ImportCopy
    data object TooLarge : ImportCopy
    data object Failed : ImportCopy
}
