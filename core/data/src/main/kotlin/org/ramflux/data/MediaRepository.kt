// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.File

/** A normalised outbox file ready for `attachment.prepare.*`; [fileName] is what recipients see. */
data class StagedMedia(val file: File, val fileName: String, val mime: String) {
    /** Bytes of the staged file, read once when it was staged. */
    val size: Long = file.length()
    override fun toString() = "StagedMedia(<redacted>)"
}

/** Where a send goes: a direct conversation (the peer is addressed by the conversation id) or a group. */
data class MediaDestination(val conversation: String, val group: String?) {
    override fun toString() = "MediaDestination(<redacted>)"
}

/** Decrypted bytes this device holds for one conversation. */
data class ConversationUsage(val conversation: String, val bytes: Long) {
    override fun toString() = "ConversationUsage(<redacted>)"
}

/** The media store of one account: decrypted originals by conversation plus staged outgoing files. */
data class MediaStorage(val total: Long, val outbox: Long, val conversations: List<ConversationUsage>) {
    override fun toString() = "MediaStorage(<redacted>)"
}

/**
 * The automatic eviction policy (media design 5.7). Decrypted originals are never removed silently:
 * after the 24-hour relay window the local copy may be the only one, so they go only when the person
 * clears them, the message is erased or the account is removed. Staged outgoing files older than the
 * prepare hard deadline can no longer be sent and are removed; decoded images live only in memory and
 * are dropped on lock and memory pressure.
 */
object MediaRetention {
    const val OUTBOX_MAX_AGE_MS = 24L * 60 * 60 * 1000
}

/** Streaming plaintext digest the SDK checks in `attachment.prepare.finish` (unkeyed BLAKE3, 64 hex). */
interface PlaintextDigest {
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun hex(): String
}

/**
 * Encrypted attachments through the SDK (media design 3.1, 3.2, 5.5-5.7). Decrypted originals live only
 * in the app-private no-backup media store, keyed by account, message id and index, never by the
 * sender's file name.
 */
interface MediaRepository {
    /** The decrypted original when this device already holds it. */
    fun localFile(account: String, attachment: MediaAttachment): File?

    /** [localFile] for many attachments, off the main thread. */
    suspend fun locate(account: String, attachments: List<MediaAttachment>): List<File?> = attachments.map { localFile(account, it) }

    /** Imports and stores one attachment; [progress] receives each state change. Cancellation cancels the import. */
    suspend fun download(account: String, attachment: MediaAttachment, progress: (TransferState) -> Unit = {}): TransferState

    /** Prepares every staged file, then sends them with [caption] as one message (1 to 16 items). */
    suspend fun send(
        account: String,
        destination: MediaDestination,
        caption: String,
        items: List<StagedMedia>,
        operationId: String,
        progress: (TransferState) -> Unit = {},
    ): ClientResult<SendReceipt>

    /** Retires the relay grants of an erased message (the SDK does not do it on erase, request R7). */
    suspend fun revoke(account: String, message: String): ClientResult<Unit>

    /** Removes every stored and derived file of [message] (tombstone, local delete, revoke). */
    suspend fun purge(account: String, message: String)

    /** Removes every media file of [account] (logout, account removal). */
    suspend fun purgeAccount(account: String)

    /** Bytes held in the media store of [account]. */
    suspend fun usage(account: String): Long

    /** The app-private folder where outgoing files are staged before prepare. */
    fun outbox(account: String): File?

    /**
     * Copies a decrypted original into the outbox so it can be sent again with a new key (forwarding,
     * media design 6 rule 8); null when it cannot be copied.
     */
    suspend fun copyToOutbox(account: String, source: File, fileName: String, mime: String): StagedMedia? = null

    /** Usage by conversation for the storage screen, read off the main thread. */
    suspend fun storage(account: String): MediaStorage = MediaStorage(usage(account), 0, emptyList())

    /** Removes every decrypted original of [conversation] (storage screen, after confirmation). */
    suspend fun purgeConversation(account: String, conversation: String) = Unit

    /** Applies [MediaRetention] at [nowMs]; returns the bytes removed. */
    suspend fun evict(account: String, nowMs: Long): Long = 0
}

class UnavailableMediaRepository : MediaRepository {
    override fun localFile(account: String, attachment: MediaAttachment): File? = null
    override suspend fun download(account: String, attachment: MediaAttachment, progress: (TransferState) -> Unit): TransferState =
        TransferState.Failed(0, retryable = false)
    override suspend fun send(
        account: String,
        destination: MediaDestination,
        caption: String,
        items: List<StagedMedia>,
        operationId: String,
        progress: (TransferState) -> Unit,
    ): ClientResult<SendReceipt> = ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun revoke(account: String, message: String): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CHAT, UnavailableReason.UNSUPPORTED_BY_CORE)
    override suspend fun purge(account: String, message: String) = Unit
    override suspend fun purgeAccount(account: String) = Unit
    override suspend fun usage(account: String): Long = 0
    override fun outbox(account: String): File? = null
}

/** Why a picked item could not be staged; each maps to its own message. */
enum class StageRefusal { UNREADABLE, VIDEO_TOO_LONG, VIDEO_TOO_LARGE }

sealed interface StageResult {
    data class Staged(val media: StagedMedia) : StageResult
    data class Refused(val reason: StageRefusal) : StageResult
}

/** Stages picker results into the account outbox (platform code; see core/media). Uris are opaque strings. */
interface MediaStager {
    /** Re-encodes an image from its pixels; null when it cannot be decoded within the caps. */
    suspend fun image(account: String, uri: String): StagedMedia?

    /** Copies a document byte for byte; null when it cannot be read or exceeds the client cap. */
    suspend fun file(account: String, uri: String): StagedMedia?

    /**
     * Anything the photo picker returns: still images are re-encoded, GIF and animated WebP keep their
     * bytes under the animation cap, videos are transcoded to the caps; [progress] reports local work.
     */
    suspend fun visual(account: String, uri: String, progress: (Float) -> Unit = {}): StageResult =
        image(account, uri)?.let { StageResult.Staged(it) } ?: StageResult.Refused(StageRefusal.UNREADABLE)

    /** [image] for a photo the system camera wrote into the app's capture folder; the capture is deleted afterwards. */
    suspend fun camera(account: String, uri: String): StagedMedia? = image(account, uri)

    /** Drops decoded images held in memory (lock, account change). */
    fun dropMemory()
}

object NoMediaStager : MediaStager {
    override suspend fun image(account: String, uri: String): StagedMedia? = null
    override suspend fun file(account: String, uri: String): StagedMedia? = null
    override fun dropMemory() = Unit
}

/** Implemented by the message repository, which owns the one SDK transport of the composition root. */
interface AttachmentHost {
    fun repositoryTransport(): RepositoryTransport
}
