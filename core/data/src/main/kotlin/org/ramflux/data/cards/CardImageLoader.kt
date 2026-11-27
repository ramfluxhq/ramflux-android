// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data.cards

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import org.ramflux.data.DefaultConversationsRepository
import org.ramflux.data.RepositoryContext
import org.ramflux.data.RepositoryTransport
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.SecretBody
import org.ramflux.sdk.SecretReply

/** One decoded card image, ready to draw. */
class CardImage(val bitmap: Bitmap, val width: Int, val height: Int) {
    override fun toString() = "CardImage(<redacted>)"
}

/** Where a drawn card gets the bytes of its Image nodes. A null answer is the "Image unavailable" box. */
interface CardImageSource {
    /** Reads and decodes the image of [attachment] (the node's `attachment` object) of card [ref]; null when it cannot be shown. */
    suspend fun load(ref: String, attachment: JsonObject): CardImage?

    companion object {
        /** No images: every Image node reads as unavailable (the gallery and hosts without the object API). */
        val None: CardImageSource = object : CardImageSource {
            override suspend fun load(ref: String, attachment: JsonObject): CardImage? = null
        }
    }
}

/**
 * Reads the images of card Image nodes through the object API (`object.fetch`, then `object.get.begin/read/finish`), the same
 * path core documents for message attachments. The bytes only ever come from a core `attachment` selector that the SDK checked
 * against the card's conversation and manifest digest: no URL, path or data URI of the tree is read. An image is kept in memory
 * only, at most 8 MiB encoded, decoded within the README limits (each side at most 4096, at most 4,194,304 pixels, at most
 * 32 MiB decoded per session, one decode at a time), and released when its session is disposed, forgotten or locked.
 */
internal class CardImageLoader(
    transport: RepositoryTransport,
    private val now: () -> Long = System::nanoTime,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val objects = DefaultConversationsRepository(transport)
    private val decoding = Mutex()
    private val guard = Any()
    /** Numbers the reads: core discards an earlier open read with the same operation id, so each read has its own. */
    private val reads = AtomicLong()
    private val sessions = HashMap<String, Held>()

    private class Held {
        val images = HashMap<String, CardImage>()
        var decoded = 0L
    }

    private val sources = HashMap<String, CardImageSource>()

    /** One stable source per account, so a card that reads it again is equal to the one it was. */
    fun source(account: String): CardImageSource = synchronized(guard) {
        sources.getOrPut(account) {
            object : CardImageSource {
                override suspend fun load(ref: String, attachment: JsonObject): CardImage? = this@CardImageLoader.load(account, ref, attachment)
            }
        }
    }

    /** Drops the images of one card. */
    fun release(account: String, ref: String) {
        synchronized(guard) { sessions.remove(key(account, ref)) }
    }

    /** Drops the images of every card of the account. */
    fun releaseAccount(account: String) {
        synchronized(guard) { sessions.keys.removeAll { it.startsWith(account + SEP) } }
    }

    private suspend fun load(account: String, ref: String, attachment: JsonObject): CardImage? {
        if (attachment.text("retrieval") != "object") return null
        val objectId = attachment.text("object_id")?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
        val digest = attachment.text("manifest_digest")?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
        if ((attachment.long("size") ?: 0L) > MAX_BYTES) return null
        val slotKey = objectId + SEP + digest
        val held = synchronized(guard) {
            val session = sessions.getOrPut(key(account, ref)) { Held() }
            session.images[slotKey]?.let { return it }
            session
        }
        val operation = operationId(account, ref, objectId)
        if (attachment.text("transfer_state") != "available" && !fetched(account, objectId, operation)) return null
        val bytes = read(account, objectId, "$operation-${reads.incrementAndGet()}") ?: return null
        val image = try {
            decode(bytes, held)
        } finally {
            bytes.fill(0)
        } ?: return null
        synchronized(guard) {
            // The card may have been released while the bytes were read: then the bitmap is dropped, not kept.
            if (sessions[key(account, ref)] === held) held.images[slotKey] = image else return null
        }
        return image
    }

    /** Waits (bounded) for the object to be available, repeating the fetch under the same operation id so it resumes. */
    private suspend fun fetched(account: String, objectId: String, operation: String): Boolean {
        val deadline = now() + FETCH_WINDOW_NS
        while (true) {
            val reply = body(
                objects.objectFetch(
                    RepositoryContext(account),
                    SecretBody.fields {
                        publicText("object_id", objectId)
                        publicText("operation_id", operation)
                    },
                ),
            ) ?: return false
            when (reply.text("transfer_state")) {
                "available" -> return true
                "failed" -> if (reply.flag("retryable") != true) return false
                else -> Unit
            }
            if (now() >= deadline) return false
            pause(RETRY_MS)
        }
    }

    /** The plaintext of the object, at most [MAX_BYTES], or null on any refusal or mismatch. */
    private suspend fun read(account: String, objectId: String, operation: String): ByteArray? {
        val context = RepositoryContext(account)
        var out: ByteArray? = null
        try {
            // Begin inside the try: core spools the plaintext on begin, so every path (cancellation included) must reach
            // the finish in `finally`, which is a no-op for an id core never opened.
            val begun = body(
                objects.objectGetBegin(
                    context,
                    SecretBody.fields {
                        publicText("object_id", objectId)
                        publicText("operation_id", operation)
                        publicUnsigned("protocol_version", PROTOCOL_VERSION)
                    },
                ),
            ) ?: return null
            // The reply names the object it opened: it must be the one the slot selected.
            if (begun.text("object_id") != objectId) return null
            val total = begun.long("total_len")?.takeIf { it in 1..MAX_BYTES } ?: return null
            val whole = ByteArray(total.toInt())
            out = whole
            var offset = 0
            while (offset < whole.size) {
                val length = minOf(CHUNK, whole.size - offset)
                val reply = body(
                    objects.objectGetRead(
                        context,
                        SecretBody.fields {
                            publicText("operation_id", operation)
                            publicUnsigned("offset", offset.toULong())
                            publicUnsigned("len", length.toULong())
                        },
                    ),
                ) ?: return null
                val data = try {
                    Base64.getUrlDecoder().decode(reply.text("data_base64") ?: return null)
                } catch (_: IllegalArgumentException) {
                    return null
                }
                if (data.size != length) return null
                try {
                    data.copyInto(whole, offset)
                } finally {
                    data.fill(0)
                }
                offset += length
            }
            val done = whole
            out = null
            return done
        } finally {
            out?.fill(0)
            withContext(NonCancellable) {
                body(objects.objectGetFinish(context, SecretBody.fields { publicText("operation_id", operation) }))
            }
        }
    }

    /** Bounds first, then one decode at a time; refused when a limit is exceeded or the session budget would be. */
    private suspend fun decode(bytes: ByteArray, held: Held): CardImage? = decoding.withLock {
        withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE) return@withContext null
            if (width.toLong() * height > MAX_PIXELS) return@withContext null
            if (bounds.outMimeType != "image/png" && bounds.outMimeType != "image/jpeg") return@withContext null
            val cost = width.toLong() * height * BYTES_PER_PIXEL
            val reserved = synchronized(guard) {
                if (held.decoded + cost > MAX_DECODED) false else { held.decoded += cost; true }
            }
            if (!reserved) return@withContext null
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (bitmap == null) {
                synchronized(guard) { held.decoded -= cost }
                return@withContext null
            }
            CardImage(bitmap, width, height)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun body(result: CoreResult<SecretReply>): JsonObject? = when (result) {
        is CoreResult.Failure -> null
        is CoreResult.Success -> try {
            result.value.useOnce { raw -> json.decodeFromStream(JsonElement.serializer(), ByteArrayInputStream(raw)) as? JsonObject }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            result.value.close()
        }
    }

    /** One operation id per object and card session, so a repeated fetch resumes instead of starting again. */
    private fun operationId(account: String, ref: String, objectId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((account + SEP + ref + SEP + objectId).toByteArray(Charsets.UTF_8))
        return "card-image-" + digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun key(account: String, ref: String) = account + SEP + ref

    private companion object {
        const val SEP = "\u0000"
        const val MAX_ID = 256
        const val MAX_BYTES = 8L * 1024 * 1024
        const val CHUNK = 256 * 1024
        const val PROTOCOL_VERSION = 1uL
        const val MAX_SIDE = 4096
        const val MAX_PIXELS = 4_194_304L
        const val MAX_DECODED = 32L * 1024 * 1024
        const val BYTES_PER_PIXEL = 4L
        const val FETCH_WINDOW_NS = 60_000_000_000L
        const val RETRY_MS = 1_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
