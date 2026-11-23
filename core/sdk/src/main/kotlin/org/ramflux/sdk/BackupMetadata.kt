// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** One backup or restore job (`ramflux-sdk` `backup/model.rs` `BackupJob`); the archive path stays host-only. */
data class BackupJobDisplay(
    val id: String,
    val kind: String,
    val state: String,
    val seq: ULong,
    val path: String,
    val createdAt: Long,
    val finishedAt: Long?,
    val category: String,
    val itemsDone: Long,
    val itemsTotal: Long?,
    val bytesDone: Long,
    val bytesTotal: Long?,
    val archiveAccount: String?,
    val archiveLabel: String?,
    val archiveCreatedAt: Long?,
    val merge: Boolean?,
    val installedAccount: String?,
    val failure: String?,
    val retryable: Boolean,
) {
    override fun toString() = "BackupJobDisplay($id, $kind, $state)"
}

object BackupMetadataReader {
    private val KINDS = setOf("backup", "restore")
    private val STATES = setOf("preparing", "writing", "verifying", "verified", "installing", "done", "failed", "cancelled")

    /** `{"job": row}` from create, verify, install and cancel. */
    fun job(reply: SecretReply): CoreResult<BackupJobDisplay> = consume(reply) { f -> row(f, requireNotNull(f.field(f.root, "job"))) }

    /** `{"jobs": [row]}` from `account.backup.jobs`; the SDK keeps a bounded registry. */
    fun jobs(reply: SecretReply): CoreResult<List<BackupJobDisplay>> = consume(reply) { f ->
        f.rows("jobs", 1_024).map { row(f, it) }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
    }

    private fun row(f: DisplayFields, row: ByteJson.Node): BackupJobDisplay {
        val kind = text(f, row, "kind").also { require(it in KINDS) }
        val state = text(f, row, "state").also { require(it in STATES) }
        val progress = requireNotNull(f.field(row, "progress"))
        val archive = f.field(row, "archive")?.takeIf { it.kind == 123 }
        val installed = f.field(row, "installed_account")?.takeIf { it.kind == 123 }
        val error = f.field(row, "error")?.takeIf { it.kind == 123 }
        return BackupJobDisplay(
            text(f, row, "job_id"), kind, state, f.unsigned(row, "seq"), text(f, row, "path"),
            requireNotNull(f.optionalUnsigned(row, "created_at")), f.optionalUnsigned(row, "finished_at"),
            text(f, progress, "category"), requireNotNull(f.optionalUnsigned(progress, "items_done")), f.optionalUnsigned(progress, "items_total"),
            requireNotNull(f.optionalUnsigned(progress, "bytes_done")), f.optionalUnsigned(progress, "bytes_total"),
            archive?.let { text(f, it, "local_account_id") }, archive?.let { f.text(it, "account_label") },
            archive?.let { f.optionalUnsigned(it, "created_at") }, archive?.let { f.boolean(it, "merge") },
            installed?.let { text(f, it, "local_account_id") },
            error?.let { text(f, it, "code") }, error?.let { f.optionalBoolean(it, "retryable") } ?: false,
        )
    }
    private fun text(f: DisplayFields, row: ByteJson.Node, key: String) = requireNotNull(f.text(row, key, true))
    private fun <T> consume(reply: SecretReply, block: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(block(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
