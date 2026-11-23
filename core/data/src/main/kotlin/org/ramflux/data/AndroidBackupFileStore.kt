// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Archives live in `noBackupFilesDir/backups`: app-private, excluded from cloud and device-transfer backup,
 * never in shared storage. Stale files left by a killed process are removed when the store is created.
 */
internal class AndroidBackupFileStore(context: Context) : BackupFileStore {
    private val resolver = context.contentResolver
    private val directory = File(context.noBackupFilesDir, "backups").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    override fun newArchivePath(): String = File(directory, UUID.randomUUID().toString() + ".rfbackup").absolutePath

    override suspend fun copyOut(path: String, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        if (!owns(path)) return@withContext false
        runCatching {
            File(path).inputStream().use { input ->
                (resolver.openOutputStream(destination, "wt") ?: return@runCatching false).use { output ->
                    copyBounded(input, output, Long.MAX_VALUE) { ensureActive() } != null
                }
            }
        }.getOrDefault(false)
    }

    override suspend fun copyIn(source: Uri, limit: Long): ImportCopy = withContext(Dispatchers.IO) {
        // The declared size is checked first; the copy itself counts bytes, since a provider may not report it.
        val declared = runCatching {
            resolver.query(source, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()
        if (declared != null && declared > limit) return@withContext ImportCopy.TooLarge
        val target = File(newArchivePath())
        val outcome = runCatching {
            (resolver.openInputStream(source) ?: return@runCatching ImportCopy.Failed).use { input ->
                target.outputStream().use { output ->
                    copyBounded(input, output, limit) { ensureActive() } ?: return@runCatching ImportCopy.TooLarge
                }
            }
            ImportCopy.Copied(target.absolutePath)
        }.getOrDefault(ImportCopy.Failed)
        if (outcome !is ImportCopy.Copied) target.delete()
        outcome
    }

    override fun delete(path: String) {
        if (owns(path)) File(path).delete()
    }

    override fun owns(path: String): Boolean = runCatching {
        File(path).canonicalFile.parentFile == directory.canonicalFile
    }.getOrDefault(false)
}

/**
 * Copies [input] to [output] and returns the byte count, or null as soon as more than [limit] bytes arrive
 * (nothing past the limit is written). [check] runs before every chunk, for cancellation.
 */
internal fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, limit: Long, check: () -> Unit = {}): Long? {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        check()
        val read = input.read(buffer)
        if (read < 0) return total
        total += read
        if (total > limit) return null
        output.write(buffer, 0, read)
    }
}
