// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class MessageHistoryEntry(val id: String, val conversationId: String, val sender: String?, val own: Boolean, val deleted: Boolean, val text: String?) {
    override fun toString() = "MessageHistoryEntry(<redacted>)"
}
object MessageHistoryMetadataReader {
    /** Approved plaintext comes only from body_utf8. Deleted rows never disclose sender/content. */
    fun read(reply: SecretReply): CoreResult<List<MessageHistoryEntry>> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val rows = fields.rows("messages", 1000).map { row ->
                val deleted = fields.boolean(row, "deleted")
                MessageHistoryEntry(requireNotNull(fields.text(row, "message_id", true)),
                    requireNotNull(fields.text(row, "conversation_id", true)),
                    if (deleted) null else fields.text(row, "sender_id"), fields.boolean(row, "is_own"), deleted,
                    if (deleted) null else fields.text(row, "body_utf8"))
            }
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(rows)
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
