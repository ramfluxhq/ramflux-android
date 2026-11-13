// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

sealed interface TimelineMetadata { val id: String; val conversationId: String; val createdAt: Long? }
data class MessageHistoryEntry(override val id: String, override val conversationId: String, val sender: String?, val own: Boolean, val deleted: Boolean, val text: String?, override val createdAt: Long? = null) : TimelineMetadata {
    override fun toString() = "MessageHistoryEntry(<redacted>)"
}
data class CallHistoryEntry(override val id: String, override val conversationId: String, override val createdAt: Long?) : TimelineMetadata {
    override fun toString() = "CallHistoryEntry(<redacted>)"
}
object MessageHistoryMetadataReader {
    /** Approved plaintext comes only from body_utf8. Calls have no message ownership/deletion flags. */
    fun read(reply: SecretReply): CoreResult<List<TimelineMetadata>> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val rows = fields.rows("messages", 1000).map { row ->
                val id = requireNotNull(fields.text(row, "message_id", true))
                val conversation = requireNotNull(fields.text(row, "conversation_id", true))
                val createdAt = fields.optionalSigned(row, "created_at")
                when (val kind = fields.text(row, "kind")) {
                    "call" -> CallHistoryEntry(id, conversation, createdAt)
                    null, "message" -> {
                        val deleted = fields.boolean(row, "deleted")
                        MessageHistoryEntry(id, conversation,
                            if (deleted) null else fields.text(row, "sender_id"), fields.boolean(row, "is_own"), deleted,
                            if (deleted) null else fields.text(row, "body_utf8"), createdAt)
                    }
                    else -> throw IllegalArgumentException("unsupported timeline kind: " + kind.length)
                }
            }
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(rows)
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
