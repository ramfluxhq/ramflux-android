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
