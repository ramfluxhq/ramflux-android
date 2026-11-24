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
    val muted: Boolean = false,
    /** The recipient root commitment of a direct conversation's route (public); null for groups and unknown. */
    val peer: String? = null,
    /** The group of a group conversation (public id, needed to address group sends); null for direct rows. */
    val groupId: String? = null,
    /** The direct peer's display name as the SDK resolves it (`peer_display_name`); null for groups and unknown. */
    val peerName: String? = null,
)
/** One event of the directory topics; [row] is null when the frame carries no usable row. */
data class ConversationDirectoryEvent(val topic: String?, val row: ConversationDirectoryEntry?)
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
                    fields.text(row, "kind"), participants, fields.optionalBoolean(row, "muted") == true, directPeer(fields, row),
                    groupOf(fields, row), peerName(fields, row))
            }
            require(rows.map { it.id }.distinct().size == rows.size)
            CoreResult.Success(ConversationDirectoryMetadata(rows, fields.text(fields.root, "next_cursor")))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }

    /**
     * The topic of one directory event and the row it carries: the body of `conversation.changed`, or
     * the `conversation` field of `message.received`. The row is null when the frame has none that is
     * usable (the caller reloads). A `{"id":…,"removed":true}` body becomes a hidden row.
     */
    fun change(reply: SecretReply): ConversationDirectoryEvent = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val topic = fields.text(fields.root, "method")
            ConversationDirectoryEvent(topic, eventRow(fields, topic))
        }
    } catch (_: Exception) { ConversationDirectoryEvent(null, null) }
    finally { reply.close() }
}

/** The recipient commitment of a direct route; a route that does not read is omitted, never a failure. */
private fun directPeer(fields: DisplayFields, row: ByteJson.Node): String? = try {
    fields.field(row, "direct_route")?.takeIf { it.kind == 123 }
        ?.let { fields.text(it, "recipient_principal_commitment") }?.takeIf { it.length in 1..128 }
} catch (_: Exception) {
    null
}

/** The SDK's name of a direct peer (CHAT-ACTIONS-01); a value that does not read is omitted, never a failure. */
private fun peerName(fields: DisplayFields, row: ByteJson.Node): String? = try {
    fields.field(row, "peer_display_name")?.takeIf { it.kind == 34 }?.let { fields.text(row, "peer_display_name") }
        ?.takeIf { it.isNotBlank() && it.length <= 256 }
} catch (_: Exception) {
    null
}

/** The `group_id` of a row; a value that does not read is omitted, never a failure. */
private fun groupOf(fields: DisplayFields, row: ByteJson.Node): String? = try {
    fields.field(row, "group_id")?.takeIf { it.kind == 34 }?.let { fields.text(row, "group_id") }?.takeIf { it.length in 1..256 }
} catch (_: Exception) {
    null
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

private fun eventRow(fields: DisplayFields, topic: String?): ConversationDirectoryEntry? = try {
    val body = fields.field(fields.root, "body")?.takeIf { it.kind == 123 }
    val row = when (topic) {
        "conversation.changed" -> body
        "message.received" -> body?.let { fields.field(it, "conversation") }?.takeIf { it.kind == 123 }
        else -> null
    }
    val id = row?.let { fields.text(it, "id") }?.takeIf { it.isNotEmpty() && it.length <= 4096 }
    when {
        row == null || id == null -> null
        fields.optionalBoolean(row, "removed") == true ->
            ConversationDirectoryEntry(id, null, 0uL, archived = false, hidden = true, isUnread = false, lastActivity = null)
        else -> ConversationDirectoryEntry(id, fields.text(row, "title"), fields.unsigned(row, "unread_count"), fields.boolean(row, "archived"),
            fields.boolean(row, "hidden"), fields.boolean(row, "is_unread"), fields.optionalSigned(row, "last_activity"),
            fields.text(row, "kind"), participantNames(fields, row), fields.optionalBoolean(row, "muted") == true,
            peer = directPeer(fields, row), groupId = groupOf(fields, row), peerName = peerName(fields, row))
    }
} catch (_: Exception) { null }
