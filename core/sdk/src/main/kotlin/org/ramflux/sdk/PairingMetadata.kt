// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/**
 * A `device.pair.begin` result. [token] is the SDK's `InviteToken` object as owned UTF-8 bytes: a show-once
 * secret that is never decoded into a String or JSON tree by this reader.
 */
class PairingInvitationDisplay(val operationId: String, val generation: ULong, val expiresAt: Long, val token: SecretBytes) {
    override fun toString() = "PairingInvitationDisplay(<redacted>)"
}

/** One pairing `Update` (`ramflux-protocol` `custody/pairing/local_dto.rs`). */
data class PairingUpdateDisplay(
    val operationId: String,
    val generation: ULong,
    val phase: String,
    val expiresAt: Long,
    val transcriptHash: String?,
    val emojiIndices: List<Int>?,
    val peerDevice: String?,
    val peerName: String?,
    val capabilities: List<String>,
    val leaseExpiresAt: Long,
    val proposalHash: String?,
    val result: String?,
)

/** A pairing call outcome: an invitation, an update, or the SDK's named pairing failure. */
sealed interface PairingOutcomeDisplay {
    class Invitation(val value: PairingInvitationDisplay) : PairingOutcomeDisplay
    data class Update(val value: PairingUpdateDisplay) : PairingOutcomeDisplay
    data class Refused(val code: String, val retryAfterMillis: Long?) : PairingOutcomeDisplay
}

/** Reads a pairing `LocalResult` (`{"schema","version","outcome":{"tag":...}}`). */
object PairingMetadataReader {
    private val PHASES = setOf(
        "invited", "commitments", "revealed", "sas_pending", "mutually_confirmed", "proposal_pending", "prepared",
        "submitted", "acknowledged", "locally_installed", "completed", "cancelled", "expired", "rejected",
    )

    fun read(reply: SecretReply): CoreResult<PairingOutcomeDisplay> = try {
        reply.useOnce { raw ->
            val f = DisplayFields(raw)
            val outcome = requireNotNull(f.field(f.root, "outcome"))
            when (f.text(outcome, "tag", true)) {
                "begin" -> {
                    val value = requireNotNull(f.field(outcome, "value"))
                    val token = requireNotNull(f.field(value, "invite_token"))
                    require(token.kind == 123)
                    CoreResult.Success(
                        PairingOutcomeDisplay.Invitation(
                            PairingInvitationDisplay(
                                requireNotNull(f.text(value, "operation_id", true)),
                                f.unsigned(value, "generation"),
                                expiry(f, value, "expires_at"),
                                SecretBytes.take(f.copy(token)),
                            )
                        )
                    )
                }
                "update" -> CoreResult.Success(PairingOutcomeDisplay.Update(update(f, requireNotNull(f.field(outcome, "value")))))
                "error" -> {
                    val error = requireNotNull(f.field(outcome, "error"))
                    CoreResult.Success(PairingOutcomeDisplay.Refused(requireNotNull(f.text(error, "code", true)), f.optionalUnsigned(error, "retry_after_ms")))
                }
                else -> CoreResult.Failure(CoreError.InvalidResponse())
            }
        }
    } catch (_: Exception) {
        CoreResult.Failure(CoreError.InvalidResponse())
    } finally { reply.close() }

    private fun update(f: DisplayFields, value: ByteJson.Node): PairingUpdateDisplay {
        val phase = requireNotNull(f.text(value, "phase", true))
        require(phase in PHASES)
        val emoji = f.field(value, "emoji_indices")?.takeIf { it.kind == 91 }?.let { node ->
            require(node.children.size == 7)
            node.children.map { index ->
                require(index.kind in 48..57)
                f.raw(index).toInt().also { require(it in 0..63) }
            }
        }
        val peer = f.field(value, "peer_ref")?.takeIf { it.kind == 123 }
        return PairingUpdateDisplay(
            requireNotNull(f.text(value, "operation_id", true)),
            f.unsigned(value, "generation"),
            phase,
            expiry(f, value, "expires_at"),
            f.text(value, "transcript_hash"),
            emoji,
            peer?.let { requireNotNull(f.text(it, "device_id", true)) },
            f.text(value, "peer_name"),
            f.texts(value, "capabilities", 5, 0, 32),
            expiry(f, value, "lease_expires_at"),
            f.text(value, "proposal_hash"),
            f.text(value, "result"),
        )
    }

    private fun expiry(f: DisplayFields, row: ByteJson.Node, key: String): Long =
        requireNotNull(f.optionalUnsigned(row, key)).also { require(it in 0L..253402300799L) }
}
