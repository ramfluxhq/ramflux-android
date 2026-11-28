// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

/** One staged attachment of a queued send: its outbox file and what recipients see. */
data class QueuedMedia(val path: String, val fileName: String, val mime: String) {
    override fun toString() = "QueuedMedia(<redacted>)"
}

/**
 * One send the SDK has not admitted yet. [operation] is its idempotency key: a resumed send reuses it, so the SDK
 * reconciles a send that was in flight when the app was locked or stopped instead of sending it twice.
 */
data class QueuedSend(
    val id: String,
    val operation: String,
    val text: String,
    val sentAt: Long,
    val definite: Boolean = false,
    val media: List<QueuedMedia> = emptyList(),
    /**
     * True for a send the SDK refused until a changed identity is reviewed. It comes back as a failed bubble and is never
     * resent by itself; only an explicit user resend sends it again.
     */
    val blocked: Boolean = false,
) {
    override fun toString() = "QueuedSend(<redacted>)"
}

/**
 * The chat outbox per conversation: sends that were not admitted by the SDK survive locking and process death, as
 * ciphertext in app-private no-backup storage under a non-exportable Keystore key, and are resumed with their
 * original operation ids on the next open. Admitted and discarded sends leave it.
 */
interface ChatOutboxRepository {
    suspend fun load(account: String, conversation: String): List<QueuedSend>
    suspend fun save(account: String, conversation: String, sends: List<QueuedSend>): ClientResult<Unit>
    /** Removes every queued send of [account], for logout and account removal. */
    suspend fun clearAccount(account: String)
}

/** Files are named by a digest of the account and conversation ids, so no id appears on disk. */
class EncryptedChatOutboxRepository(private val root: File, private val cipher: DraftCipher) : ChatOutboxRepository {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "chat-outbox"), AesGcmDraftCipher.keystore(OUTBOX_ALIAS))

    private val guard = Mutex()
    override suspend fun load(account: String, conversation: String): List<QueuedSend> = io {
        val file = file(account, conversation) ?: return@io emptyList()
        if (!file.isFile) return@io emptyList()
        val plain = try {
            cipher.open(file.readBytes(), associated(account, conversation))
        } catch (_: Exception) {
            // An outbox that no longer opens (key replaced, file damaged) is discarded, never guessed at.
            file.delete()
            return@io emptyList()
        }
        try {
            decode(String(plain, Charsets.UTF_8)).take(MAX_SENDS)
        } catch (_: Exception) {
            file.delete()
            emptyList()
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun save(account: String, conversation: String, sends: List<QueuedSend>): ClientResult<Unit> = io {
        val file = file(account, conversation) ?: return@io ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        if (sends.isEmpty()) {
            file.delete()
            return@io ClientResult.Ready(Unit)
        }
        val plain = encode(sends.take(MAX_SENDS)).toByteArray(Charsets.UTF_8)
        try {
            val sealed = cipher.seal(plain, associated(account, conversation))
            file.parentFile?.mkdirs()
            val staging = File(file.parentFile, file.name + ".tmp")
            staging.writeBytes(sealed)
            if (!staging.renameTo(file)) {
                staging.delete()
                return@io ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
            }
            ClientResult.Ready(Unit)
        } catch (_: Exception) {
            ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        } finally {
            plain.fill(0)
        }
    }

    override suspend fun clearAccount(account: String) {
        io { directory(account)?.deleteRecursively() }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { guard.withLock { block() } }

    private fun directory(account: String): File? = if (!usable(account)) null else File(root, digest(account))

    private fun file(account: String, conversation: String): File? {
        if (!usable(conversation)) return null
        val folder = directory(account) ?: return null
        return File(folder, digest(account + "\u0000" + conversation))
    }

    private fun associated(account: String, conversation: String) = ("outbox\u0000" + account + "\u0000" + conversation).toByteArray(Charsets.UTF_8)

    private fun usable(value: String) = value.isNotEmpty() && value.toByteArray(Charsets.UTF_8).size <= 256

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun encode(sends: List<QueuedSend>): String = buildJsonArray {
        for (send in sends) {
            add(
                buildJsonObject {
                    put("id", JsonPrimitive(send.id))
                    put("operation", JsonPrimitive(send.operation))
                    put("text", JsonPrimitive(send.text))
                    put("sent_at", JsonPrimitive(send.sentAt))
                    put("definite", JsonPrimitive(send.definite))
                    put("blocked", JsonPrimitive(send.blocked))
                    put(
                        "media",
                        buildJsonArray {
                            send.media.forEach { media ->
                                add(
                                    buildJsonObject {
                                        put("path", JsonPrimitive(media.path))
                                        put("file_name", JsonPrimitive(media.fileName))
                                        put("mime", JsonPrimitive(media.mime))
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }
    }.toString()

    private fun decode(text: String): List<QueuedSend> = (Json.parseToJsonElement(text) as JsonArray).map { element ->
        val row = element as JsonObject
        fun string(value: JsonObject, key: String) = requireNotNull((value[key] as? JsonPrimitive)?.takeIf { it.isString }?.content)
        QueuedSend(
            string(row, "id"),
            string(row, "operation"),
            string(row, "text"),
            requireNotNull((row["sent_at"] as? JsonPrimitive)?.longOrNull),
            (row["definite"] as? JsonPrimitive)?.booleanOrNull ?: false,
            ((row["media"] as? JsonArray) ?: JsonArray(emptyList())).map { media ->
                val item = media as JsonObject
                QueuedMedia(string(item, "path"), string(item, "file_name"), string(item, "mime"))
            },
            (row["blocked"] as? JsonPrimitive)?.booleanOrNull ?: false
        )
    }

    private companion object {
        const val MAX_SENDS = 64
        const val OUTBOX_ALIAS = "org.ramflux.chat.outbox.v1"
    }
}
