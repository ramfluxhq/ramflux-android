// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class ConversationDirectoryEntry(val id: String, val title: String?, val unread: ULong, val archived: Boolean, val hidden: Boolean)
data class ConversationDirectoryMetadata(val rows: List<ConversationDirectoryEntry>, val nextCursor: String?)
object ConversationDirectoryMetadataReader {
    /** No routing records, keys, peer credentials or message ciphertext are materialized. */
    fun read(reply: SecretReply): CoreResult<ConversationDirectoryMetadata> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val rows = fields.rows("conversations").map { row ->
                ConversationDirectoryEntry(requireNotNull(fields.text(row, "id", true)), fields.text(row, "title"),
                    fields.unsigned(row, "unread_count"), fields.boolean(row, "archived"), fields.boolean(row, "hidden"))
            }
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(ConversationDirectoryMetadata(rows, fields.text(fields.root, "next_cursor")))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
