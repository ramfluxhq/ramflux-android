// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands one decrypted attachment to the system at the person's explicit request (media design 4.1, 4.4,
 * 5.7 (e)): a transient copy in the cache hand-over folder behind a FileProvider read grant, removed the
 * next time a hand-over starts. The stored original is never exposed. Names are sanitised for disk.
 */
internal object ChatFileHandover {
    private const val FOLDER = "media-handover"
    private const val CAPTURE = "media-capture"
    private val UNSAFE = setOf('/', '\\') + (0x202A..0x202E).map { it.toChar() } + (0x2066..0x2069).map { it.toChar() }

    suspend fun open(context: Context, path: String, mime: String, name: String): Boolean {
        val uri = copy(context, path, name) ?: return false
        val intent = Intent(
            Intent.ACTION_VIEW
        ).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    suspend fun share(context: Context, path: String, mime: String, name: String): Boolean {
        val uri = copy(context, path, name) ?: return false
        val send = Intent(
            Intent.ACTION_SEND
        ).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return start(context, Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** MediaStore on Android 10 and later needs no permission; Android 9 falls back to the share sheet. */
    suspend fun save(context: Context, path: String, mime: String, name: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !mime.startsWith("image/")) return share(context, path, mime, name)
        return withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, safe(name))
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } } ?: error("stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        }
    }

    /**
     * A new, empty file the system camera writes one photo into, behind a write grant. Earlier captures
     * are removed first; the stager deletes this one after re-encoding it without metadata.
     */
    suspend fun captureTarget(context: Context): Uri? = withContext(Dispatchers.IO) {
        try {
            val folder = File(context.cacheDir, CAPTURE)
            folder.deleteRecursively()
            folder.mkdirs()
            val file = File.createTempFile("capture", ".jpg", folder)
            FileProvider.getUriForFile(context, context.packageName + ".chatmedia", file)
        } catch (_: Exception) {
            null
        }
    }

    /** Removes a capture the camera did not complete. */
    suspend fun discardCapture(context: Context) = withContext(Dispatchers.IO) { File(context.cacheDir, CAPTURE).deleteRecursively() }

    private suspend fun copy(context: Context, path: String, name: String) = withContext(Dispatchers.IO) {
        try {
            val folder = File(context.cacheDir, FOLDER)
            folder.deleteRecursively()
            folder.mkdirs()
            val copy = File(folder, safe(name))
            File(path).copyTo(copy, overwrite = true)
            FileProvider.getUriForFile(context, context.packageName + ".chatmedia", copy)
        } catch (_: Exception) {
            null
        }
    }

    private fun start(context: Context, intent: Intent) = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** A disk-safe name: no separators, controls or bidi overrides; the real extension stays visible. */
    private fun safe(name: String): String {
        val cleaned = name.filter { !it.isISOControl() && it !in UNSAFE }.trim().take(120)
        return cleaned.ifEmpty { "attachment" }
    }
}
