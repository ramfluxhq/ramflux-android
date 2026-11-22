// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

/** The renderer chosen before download from the declared type; magic bytes decide after download. */
enum class MediaKind { IMAGE, ANIMATED, VIDEO, FILE }

/** The inline thumbnail a message may carry. The SDK does not fill it today (media design R1). */
data class MediaThumbnail(val mime: String, val width: Int, val height: Int, val bytes: ByteArray) {
    override fun toString() = "MediaThumbnail(<redacted>)"
    override fun equals(other: Any?) = other is MediaThumbnail && other.mime == mime && other.width == width &&
        other.height == height && other.bytes.contentEquals(bytes)
    override fun hashCode() = (mime.hashCode() * 31 + width) * 31 + height
}

/** One `content.message.media[]` item of the read model. [fileName] is peer-controlled. */
data class MediaAttachment(
    val messageId: String,
    val index: Int,
    val kind: MediaKind,
    val declaredMime: String,
    val fileName: String,
    val size: Long,
    val thumbnail: MediaThumbnail? = null,
    /** Epoch seconds after which recipients can no longer import (the 24-hour grant window). */
    val availableUntil: Long? = null,
) {
    /** Width over height from data in the message only, clamped so a row never jumps (design 5.9). */
    val aspectRatio: Float
        get() {
            val thumb = thumbnail ?: return DEFAULT_RATIO
            if (thumb.width <= 0 || thumb.height <= 0) return DEFAULT_RATIO
            return (thumb.width.toFloat() / thumb.height).coerceIn(MIN_RATIO, MAX_RATIO)
        }

    override fun toString() = "MediaAttachment(<redacted>)"

    companion object {
        const val DEFAULT_RATIO = 4f / 3f
        const val MIN_RATIO = .5f
        const val MAX_RATIO = 2f
        const val MAX_PER_MESSAGE = 16

        fun kindOf(mime: String): MediaKind {
            val lower = mime.lowercase()
            return when {
                lower == "image/gif" -> MediaKind.ANIMATED
                lower in INLINE_IMAGES -> MediaKind.IMAGE
                lower.startsWith("video/") -> MediaKind.VIDEO
                else -> MediaKind.FILE
            }
        }

        private val INLINE_IMAGES = setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif", "image/avif")
    }
}

/** Where one attachment stands on this device. */
sealed interface TransferState {
    data object Idle : TransferState
    data object Queued : TransferState
    data class Preparing(val fraction: Float?) : TransferState
    data object Uploading : TransferState
    /** The SDK reports no byte progress for imports; [fraction] stays null until it does (R4). */
    data class Downloading(val fraction: Float?) : TransferState
    data class Ready(val file: java.io.File) : TransferState {
        override fun toString() = "Ready(<redacted>)"
    }
    data class Failed(val code: Int, val retryable: Boolean) : TransferState
    data object Expired : TransferState
    data object Revoked : TransferState
    data object NotGrantee : TransferState
    /** Not downloaded automatically (stranger, policy, size); the person can still ask for it. */
    data object BlockedByPolicy : TransferState
}

/** Where a conversation sits for the auto-download decision (design 6.3). */
enum class MediaTrust { CONTACT, GROUP, UNTRUSTED }

/** Network and power inputs to [MediaPolicy]; read by the platform layer. */
data class MediaConditions(val metered: Boolean, val constrained: Boolean, val roaming: Boolean)

/** The auto-download decision as a pure function of the design's table (proposal O1). */
object MediaPolicy {
    private const val MB = 1024L * 1024L

    fun autoDownload(attachment: MediaAttachment, trust: MediaTrust, conditions: MediaConditions): Boolean {
        if (trust == MediaTrust.UNTRUSTED || conditions.roaming) return false
        val image = attachment.kind == MediaKind.IMAGE || attachment.kind == MediaKind.ANIMATED
        val size = attachment.size
        return when (trust) {
            MediaTrust.CONTACT -> when {
                conditions.constrained -> image && size <= 2 * MB
                conditions.metered -> (image && size <= 25 * MB) || (attachment.kind == MediaKind.FILE && size <= 5 * MB)
                else -> when (attachment.kind) {
                    MediaKind.IMAGE, MediaKind.ANIMATED -> size <= 25 * MB
                    MediaKind.FILE -> size <= 25 * MB
                    MediaKind.VIDEO -> size <= 50 * MB
                }
            }
            MediaTrust.GROUP -> when {
                conditions.constrained -> false
                conditions.metered -> image && size <= 2 * MB
                else -> (image || attachment.kind == MediaKind.FILE) && size <= 10 * MB
            }
            MediaTrust.UNTRUSTED -> false
        }
    }

    /** Thumbnails of untrusted senders stay hidden until the person taps (owner decision O5). */
    fun showThumbnail(trust: MediaTrust): Boolean = trust != MediaTrust.UNTRUSTED
}

/** Refusal status names from the SDK mapped to attachment states (design section 4). */
object MediaRefusals {
    fun stateFor(problem: ClientProblem): TransferState = when (problem.statusName ?: problem.check) {
        "expired" -> TransferState.Expired
        "attachment_deleted" -> TransferState.Revoked
        "not_grantee" -> TransferState.NotGrantee
        "authority_unavailable", "disk_full", "quota_exceeded" -> TransferState.Failed(problem.code, retryable = true)
        "file_too_large", "recipient_limit", "peer_upgrade_required", "large_attachments" ->
            TransferState.Failed(problem.code, retryable = false)
        else -> TransferState.Failed(problem.code, retryable = problem.outcome != ClientOutcome.REFUSED)
    }
}
