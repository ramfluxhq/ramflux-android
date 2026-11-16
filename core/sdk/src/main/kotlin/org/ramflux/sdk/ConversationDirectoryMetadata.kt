// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class ConversationDirectoryEntry(
    val id: String,
    val title: String?,
    val unread: ULong,
    val archived: Boolean,
    val hidden: Boolean,
    val isUnread: Boolean,
    val lastActivity: Long?,
    val kind: String? = null,
    val participants: List<String> = emptyList(),
)
data class ConversationDirectoryMetadata(val rows: List<ConversationDirectoryEntry>, val nextCursor: String?)
object ConversationDirectoryMetadataReader {
    /** No routing records, keys, peer credentials or message ciphertext are materialized. */
    fun read(reply: SecretReply): CoreResult<ConversationDirectoryMetadata> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val rows = fields.rows("conversations").map { row ->
                val participants = participantNames(fields, row)
                ConversationDirectoryEntry(requireNotNull(fields.text(row, "id", true)), fields.text(row, "title"),
                    fields.unsigned(row, "unread_count"), fields.boolean(row, "archived"), fields.boolean(row, "hidden"),
                    fields.boolean(row, "is_unread"), fields.optionalSigned(row, "last_activity"),
                    fields.text(row, "kind"), participants)
            }
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(ConversationDirectoryMetadata(rows, fields.text(fields.root, "next_cursor")))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}

/** A participants array that does not fit is omitted. The row and the rest of the page still list. */
private fun participantNames(fields: DisplayFields, row: ByteJson.Node): List<String> {
    val field = fields.field(row, "participants") ?: return emptyList()
    if (field.kind != 91) return emptyList()
    return try {
        fields.texts(row, "participants", bound = 1024, minimum = 0, maximum = 256)
    } catch (_: Exception) {
        emptyList()
    }
}
