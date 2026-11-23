// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ramflux.data.StagedMedia

/**
 * Turns a picker result into an outbox file (media design 4.1, 4.4, 6 rule 1). Images are re-encoded
 * from pixels only: orientation is applied by the decoder, EXIF, GPS, XMP and the original name are
 * dropped. Files are copied byte for byte.
 */
object MediaNormalizer {
    const val MAX_EDGE = 2_560
    const val JPEG_QUALITY = 82
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024

    /** GIF and animated WebP keep their bytes up to this size (media design 4.2); larger ones go as files. */
    const val MAX_ANIMATED_BYTES = 15L * 1024 * 1024

    /**
     * True when the source is an animated GIF or WebP within the pixel cap. Only the header is decoded:
     * the listener aborts before any frame is allocated.
     */
    suspend fun animated(resolver: ContentResolver, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri)
        if (mime != "image/gif" && mime != "image/webp") return@withContext false
        var animated = false
        try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(resolver, uri)) { _, info, _ ->
                animated = info.isAnimated && info.size.width.toLong() * info.size.height <= MediaImages.MAX_PIXELS
                throw HeaderRead()
            }
        } catch (_: HeaderRead) {
        } catch (_: Exception) {
            animated = false
        }
        animated
    }

    /**
     * Copies an animated image byte for byte under [MAX_ANIMATED_BYTES]. GIF comment and application
     * blocks are not rewritten yet (design 4.2 gap); the original name is replaced.
     */
    suspend fun animatedCopy(resolver: ContentResolver, uri: Uri, outbox: File): StagedMedia? = withContext(Dispatchers.IO) {
        val mime = resolver.getType(uri) ?: return@withContext null
        val extension = if (mime == "image/gif") ".gif" else ".webp"
        outbox.mkdirs()
        val file = File.createTempFile("stage", extension, outbox)
        try {
            val copied = resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: -1L
            if (copied <= 0 || copied > MAX_ANIMATED_BYTES) {
                file.delete()
                return@withContext null
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
            StagedMedia(file, "IMG_$stamp$extension", mime)
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private class HeaderRead : RuntimeException()

    suspend fun image(resolver: ContentResolver, uri: Uri, outbox: File, now: Date = Date()): StagedMedia? = withContext(Dispatchers.IO) {
        try {
            var alpha = false
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                require(width > 0 && height > 0 && width.toLong() * height <= MediaImages.MAX_PIXELS)
                val scale = MAX_EDGE.toFloat() / max(width, height)
                if (scale < 1f) decoder.setTargetSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                alpha = info.mimeType == "image/png"
            }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(now)
            val png = alpha && bitmap.hasAlpha()
            val name = "IMG_" + stamp + if (png) ".png" else ".jpg"
            outbox.mkdirs()
            val file = File.createTempFile("stage", if (png) ".png" else ".jpg", outbox)
            file.outputStream().use { out ->
                check(bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out))
            }
            bitmap.recycle()
            StagedMedia(file, name, if (png) "image/png" else "image/jpeg")
        } catch (_: Exception) {
            null
        }
    }

    /** Copies a document byte for byte; the name and type are the provider's, shown to recipients as untrusted text. */
    suspend fun file(resolver: ContentResolver, uri: Uri, outbox: File): StagedMedia? = withContext(Dispatchers.IO) {
        try {
            var name = "file"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it.take(200) }
            }
            val mime = resolver.getType(uri)?.takeIf { it.length in 1..256 } ?: "application/octet-stream"
            outbox.mkdirs()
            val file = File.createTempFile("stage", ".bin", outbox)
            val copied = resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: -1L
            if (copied < 0 || copied > MAX_FILE_BYTES) {
                file.delete()
                return@withContext null
            }
            StagedMedia(file, name.filterNot { it.isISOControl() }.ifEmpty { "file" }, mime)
        } catch (_: Exception) {
            null
        }
    }
}
