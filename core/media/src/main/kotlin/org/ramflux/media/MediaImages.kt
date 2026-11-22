// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.media

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The one image pipeline (media design 5.1, 5.8): platform `ImageDecoder` only, header-checked before
 * any pixel allocation, decoded to the target size off the main thread, held in a memory cache bounded
 * by bytes. Sources are app-private decrypted files or in-message thumbnail bytes, never URLs.
 */
object MediaImages {
    /** Refuse inline decoding above this many source pixels; the item stays available as a file. */
    const val MAX_PIXELS = 100_000_000L

    private val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val cache = object : LruCache<String, Bitmap>(budget) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val decoders = Semaphore(2)

    /** A bitmap whose long edge is at most [targetPx], or null when the bytes are not a renderable image. */
    suspend fun file(file: File, targetPx: Int): ImageBitmap? {
        val key = file.path + "#" + file.lastModified() + "@" + targetPx
        cache.get(key)?.let { return it.asImageBitmap() }
        val bitmap = decode(targetPx) { ImageDecoder.createSource(file) } ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }

    /** Decodes in-message thumbnail bytes; they are never cached on disk. */
    suspend fun bytes(key: String, bytes: ByteArray, targetPx: Int): ImageBitmap? {
        val cacheKey = "thumb:$key@$targetPx"
        cache.get(cacheKey)?.let { return it.asImageBitmap() }
        val bitmap = decode(targetPx) { ImageDecoder.createSource(ByteBuffer.wrap(bytes.copyOf())) } ?: return null
        cache.put(cacheKey, bitmap)
        return bitmap.asImageBitmap()
    }

    internal fun cached(key: String): Bitmap? = cache.get(key)

    internal fun store(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /** Drops every decoded image (lock, memory trim, account change). */
    fun clear() = cache.evictAll()

    private suspend fun decode(targetPx: Int, source: () -> ImageDecoder.Source): Bitmap? = decoders.withPermit {
        withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeBitmap(source()) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS)
                    val scale = targetPx.toFloat() / max(width, height)
                    if (scale < 1f) decoder.setTargetSize(max(1, (width * scale).toInt()), max(1, (height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** A decoded image for [path] at [targetPx] on the long edge; null until decoded or when not an image. */
@Composable
fun rememberMediaImage(path: String?, targetPx: Int): ImageBitmap? = produceState<ImageBitmap?>(null, path, targetPx) {
    value = path?.let { MediaImages.file(File(it), targetPx) }
}.value
