// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import java.text.BreakIterator

sealed interface TimelineMetadata { val id: String; val conversationId: String; val createdAt: Long? }
data class TimelineReaction(val token: String, val count: Int) {
    override fun toString() = "TimelineReaction(<redacted>)"
}
data class TimelineReceipt(val deviceId: String, val state: String) {
    override fun toString() = "TimelineReceipt(<redacted>)"
}
data class MessageHistoryEntry(
    override val id: String,
    override val conversationId: String,
    val sender: String?,
    val own: Boolean,
    val deleted: Boolean,
    val text: String?,
    override val createdAt: Long? = null,
    val revision: Long = 0,
    val edited: Boolean = false,
    val reactions: List<TimelineReaction> = emptyList(),
    val receipts: List<TimelineReceipt> = emptyList(),
    val replyTo: String? = null,
    val replyPreview: String? = null,
    val placeholder: Boolean = false,
    val service: Boolean = false,
    val attachments: List<TimelineAttachment> = emptyList(),
) : TimelineMetadata {
    override fun toString() = "MessageHistoryEntry(<redacted>)"
}

/** An inline preview carried in the read model; at most 64 KiB, 1..4096 px per side. */
class TimelineThumbnail(val mimeType: String, val width: Int, val height: Int, val bytes: ByteArray) {
    override fun toString() = "TimelineThumbnail(<redacted>)"
}

/** One `content.message.media[]` reference of a message row. Key material never reaches the host. */
data class TimelineAttachment(
    val index: Int,
    val mimeType: String,
    val fileName: String,
    val size: Long,
    val thumbnail: TimelineThumbnail?,
    val expiresAt: Long?,
) {
    override fun toString() = "TimelineAttachment(<redacted>)"
}

/** One `message.read` page: visible rows and the backwards cursor, passed back unchanged as `before_order_key`. */
data class MessageHistoryPage(val rows: List<TimelineMetadata>, val nextBefore: String?) {
    override fun toString() = "MessageHistoryPage(<redacted>)"
}
data class CallHistoryEntry(
    override val id: String,
    override val conversationId: String,
    override val createdAt: Long?,
) : TimelineMetadata {
    override fun toString() = "CallHistoryEntry(<redacted>)"
}
/** One peer typing indicator. `active` is false when the protobuf flag is omitted or the deadline has passed. */
data class PeerTyping(val conversationId: String, val active: Boolean, val expiresAtMs: Long) {
    override fun toString() = "PeerTyping(<redacted>)"
}

data class MessageDelivery(
    val conversationId: String,
    val messageId: String,
    val targetMessageId: String?,
    val state: String,
    val partial: Boolean,
) {
    override fun toString() = "MessageDelivery(<redacted>)"
}

object MessageHistoryMetadataReader {
    private const val SCHEMA = "ramflux.message.read_model.v1"
    private val HIDDEN = setOf("edit", "reaction", "receipt", "delete", "typing", "control", "surface", "task", "self_sync")
    private val RECEIPT_STATES = setOf("sent", "delivered", "read")
    private const val MAX_MS = 253_402_300_799_000L
    private const val MAX_SECONDS = 253_402_300_799L
    private const val MAX_CURSOR = 8192
    const val MAX_ATTACHMENTS = 16

    /** Generation-1 read model only. An unknown kind is a placeholder and never fails the page. */
    fun read(reply: SecretReply): CoreResult<List<TimelineMetadata>> = when (val page = page(reply)) {
        is CoreResult.Success -> CoreResult.Success(page.value.rows)
        is CoreResult.Failure -> page
    }

    /** As [read], plus the paging cursor `next_before` and each row's attachment references. */
    fun page(reply: SecretReply): CoreResult<MessageHistoryPage> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            require(fields.text(fields.root, "schema") == SCHEMA)
            val pageConversation = requireNotNull(fields.text(fields.root, "conversation_id", true))
            val rows = fields.rows("messages", 100)
            val normalized = rows.map { normalize(requireNotNull(fields.text(it, "id", true))) }
            require(normalized.all { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 256 })
            require(normalized.distinct().size == normalized.size)
            val overlays = overlays(fields, rows)
            val visible = ArrayList<TimelineMetadata>(rows.size)
            for (row in rows) {
                require(fields.text(row, "conversation_id", true) == pageConversation)
                val kind = fields.text(row, "kind").orEmpty()
                if (kind in HIDDEN) continue
                visible += rowOf(fields, row, pageConversation, kind, overlays)
            }
            CoreResult.Success(MessageHistoryPage(withPreviews(visible), nextBefore(fields)))
        }
    } catch (_: Exception) {
        CoreResult.Failure(CoreError.InvalidResponse())
    } finally {
        reply.close()
    }

    fun delivery(reply: SecretReply, conversation: String, target: String?): CoreResult<MessageDelivery> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val conversationId = requireNotNull(fields.text(fields.root, "conversation_id", true))
            require(conversationId == conversation)
            val messageId = requireNotNull(fields.text(fields.root, "message_id", true))
            val targeted = fields.text(fields.root, "target_message_id") ?: messageId
            if (target != null) require(targeted == target)
            val state = requireNotNull(fields.text(fields.root, "delivery_state", true))
            require(state == "submitted" || state == "failed" || state == "local")
            val targetId = fields.text(fields.root, "target_message_id")
            CoreResult.Success(MessageDelivery(conversationId, messageId, targetId, state, partial(fields)))
        }
    } catch (_: Exception) {
        CoreResult.Failure(CoreError.InvalidResponse())
    } finally {
        reply.close()
    }

    /** Null when the frame is not a typing indicator for one conversation. Never fails the subscription. */
    fun peerTyping(reply: SecretReply, nowMs: Long): PeerTyping? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val typing = findTyping(fields, fields.root, 0) ?: return@useOnce null
            val conversation = conversationOf(fields) ?: return@useOnce null
            val expiresAtMs = expiresMillis(fields, typing)
            val active = fields.optionalBoolean(typing, "active") == true && expiresAtMs > nowMs
            PeerTyping(conversation, active, expiresAtMs)
        }
    } catch (_: Exception) {
        null
    } finally {
        reply.close()
    }

    private fun rowOf(
        fields: DisplayFields,
        row: ByteJson.Node,
        conversation: String,
        kind: String,
        overlays: Map<String, Pair<Long, String>>,
    ): TimelineMetadata {
        val id = requireNotNull(fields.text(row, "id", true))
        val own = fields.optionalBoolean(row, "is_own") == true
        val createdAt = displaySeconds(fields, row)
        val erasure = fields.field(row, "erasure")?.takeIf { it.kind == 123 }
        val state = fields.text(row, "state")
        if (state == "tombstone" || erasure != null) return deleted(id, conversation, own, createdAt)
        if (kind == "call") return CallHistoryEntry(id, conversation, createdAt)
        val sequence = fields.field(row, "device_sequence")
        val sequenceOk = sequence == null || sequence.kind == 110 ||
            (sequence.kind == 34 && fields.text(row, "device_sequence")?.matches(Regex("\\d{20}")) == true)
        if (!sequenceOk || state == "unsupported" || kind == "unsupported" || kind !in setOf("message", "service")) {
            return placeholder(id, conversation, own, createdAt, kind == "service")
        }
        val revision = fields.optionalUnsigned(row, "revision") ?: 0L
        var resolved = messageText(fields, row, kind)
        var edited = fields.optionalBoolean(row, "edited") == true || revision > 0L
        overlays[normalize(id)]?.let { overlay ->
            if (overlay.first > revision || resolved == null) {
                resolved = overlay.second
                edited = true
            }
        }
        val shown = resolved?.takeUnless { it.toByteArray(Charsets.UTF_8).size > 65536 }
        val usable = shown != null
        return MessageHistoryEntry(
            id, conversation, if (usable) sender(fields, row) else null, own, deleted = false,
            shown, createdAt, revision, edited,
            reactions(fields, row), if (own) receipts(fields, row) else emptyList(),
            replyTo = replyTo(fields, row), placeholder = !usable, service = kind == "service",
            attachments = attachments(fields, row),
        )
    }

    private fun nextBefore(fields: DisplayFields): String? {
        val node = fields.field(fields.root, "next_before") ?: return null
        if (node.kind == 110) return null
        require(node.kind == 123)
        val cursor = fields.raw(node)
        require(cursor.length <= MAX_CURSOR)
        return cursor
    }

    private fun attachments(fields: DisplayFields, row: ByteJson.Node): List<TimelineAttachment> {
        val content = fields.field(row, "content")?.takeIf { it.kind == 123 } ?: return emptyList()
        val message = fields.field(content, "message")?.takeIf { it.kind == 123 } ?: return emptyList()
        val list = fields.field(message, "media")?.takeIf { it.kind == 91 } ?: return emptyList()
        return list.children.take(MAX_ATTACHMENTS).mapIndexedNotNull { index, item ->
            if (item.kind != 123) return@mapIndexedNotNull null
            val mime = textOf(fields, item, "mimeType", "mime_type")?.takeIf { it.length in 1..256 } ?: "application/octet-stream"
            val size = numberOf(fields, item, "size")?.takeIf { it >= 0 } ?: 0L
            val name = textOf(fields, item, "fileName", "file_name")?.takeIf { it.length <= 256 }.orEmpty()
            val manifest = (fields.field(item, "largeManifest") ?: fields.field(item, "large_manifest"))?.takeIf { it.kind == 123 }
            val expires = manifest?.let { numberOf(fields, it, "expiresAt", "expires_at") }?.takeIf { it > 0 }
            val thumbnail = (fields.field(item, "inlineThumbnail") ?: fields.field(item, "inline_thumbnail"))
                ?.takeIf { it.kind == 123 }?.let { thumbnail(fields, it) }
            TimelineAttachment(index, mime, name, size, thumbnail, expires)
        }
    }

    private fun thumbnail(fields: DisplayFields, node: ByteJson.Node): TimelineThumbnail? {
        val mime = textOf(fields, node, "mimeType", "mime_type")?.takeIf { it.length in 1..256 } ?: return null
        val width = numberOf(fields, node, "width")?.takeIf { it in 1..4096 }?.toInt() ?: return null
        val height = numberOf(fields, node, "height")?.takeIf { it in 1..4096 }?.toInt() ?: return null
        val encoded = textOf(fields, node, "encodedImage", "encoded_image") ?: return null
        if (encoded.length > 90_000) return null
        val bytes = try {
            java.util.Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty() || bytes.size > 64 * 1024) return null
        return TimelineThumbnail(mime, width, height, bytes)
    }

    /** A malformed optional field drops that field, never the page. */
    private fun textOf(fields: DisplayFields, node: ByteJson.Node, vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        val value = fields.field(node, name) ?: return@firstNotNullOfOrNull null
        if (value.kind == 34) fields.text(node, name) else null
    }

    /** pbjson writes 64-bit integers as strings; both forms are read. */
    private fun numberOf(fields: DisplayFields, node: ByteJson.Node, vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        val value = fields.field(node, name) ?: return@firstNotNullOfOrNull null
        if (value.kind != 34 && value.kind !in 48..57) return@firstNotNullOfOrNull null
        try {
            fields.optionalUnsigned(node, name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun replyTo(fields: DisplayFields, row: ByteJson.Node): String? {
        val content = fields.field(row, "content")?.takeIf { it.kind == 123 } ?: return null
        val message = fields.field(content, "message")?.takeIf { it.kind == 123 } ?: return null
        val reply = fields.field(message, "replyTo")?.takeIf { it.kind == 123 } ?: return null
        val id = fields.text(reply, "id") ?: return null
        val normalized = normalize(id)
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun messageText(fields: DisplayFields, row: ByteJson.Node, kind: String): String? {
        fields.text(row, "text")?.let { return it }
        val content = fields.field(row, "content")?.takeIf { it.kind == 123 } ?: return null
        val message = fields.field(content, "message")?.takeIf { it.kind == 123 }
        message?.let { fields.text(it, "text") }?.let { return it }
        if (kind != "service") return null
        val service = fields.field(content, "service")?.takeIf { it.kind == 123 } ?: return null
        return fields.text(service, "text")
    }

    private fun overlays(fields: DisplayFields, rows: List<ByteJson.Node>): Map<String, Pair<Long, String>> {
        val overlays = LinkedHashMap<String, Pair<Long, String>>()
        for (row in rows) {
            if (fields.text(row, "kind") != "edit") continue
            val content = fields.field(row, "content")?.takeIf { it.kind == 123 } ?: continue
            val edit = fields.field(content, "edit")?.takeIf { it.kind == 123 } ?: continue
            val targetNode = fields.field(edit, "target")?.takeIf { it.kind == 123 } ?: continue
            val target = fields.text(targetNode, "id") ?: continue
            val replacementNode = fields.field(edit, "replacement")?.takeIf { it.kind == 123 } ?: continue
            val replacement = fields.text(replacementNode, "text") ?: continue
            if (replacement.toByteArray(Charsets.UTF_8).size > 65536) continue
            val key = normalize(target)
            if (key.isEmpty()) continue
            val revision = fields.optionalUnsigned(edit, "revision") ?: fields.optionalUnsigned(row, "revision") ?: 0L
            val current = overlays[key]
            if (current == null || revision >= current.first) overlays[key] = revision to replacement
        }
        return overlays
    }

    private fun withPreviews(rows: List<TimelineMetadata>): List<TimelineMetadata> {
        val texts = rows.mapNotNull { row ->
            val message = row as? MessageHistoryEntry ?: return@mapNotNull null
            if (message.deleted || message.placeholder || message.text == null) null else normalize(message.id) to message.text
        }.toMap()
        return rows.map { row ->
            val message = row as? MessageHistoryEntry ?: return@map row
            val reply = message.replyTo ?: return@map row
            val preview = texts[reply] ?: return@map row
            message.copy(replyPreview = preview)
        }
    }

    private fun deleted(id: String, conversation: String, own: Boolean, createdAt: Long?) =
        MessageHistoryEntry(id, conversation, null, own, true, null, createdAt)

    private fun placeholder(id: String, conversation: String, own: Boolean, createdAt: Long?, service: Boolean) =
        MessageHistoryEntry(id, conversation, null, own, false, null, createdAt, placeholder = true, service = service)

    private fun sender(fields: DisplayFields, row: ByteJson.Node): String? {
        val author = fields.field(row, "author")?.takeIf { it.kind == 123 } ?: return null
        val device = fields.text(author, "device_id")?.takeIf { it.isNotEmpty() }
        return device ?: fields.text(author, "account_anchor")?.takeIf { it.isNotEmpty() }
    }

    private fun reactions(fields: DisplayFields, row: ByteJson.Node): List<TimelineReaction> {
        val node = fields.field(row, "reactions") ?: return emptyList()
        if (node.kind == 110 || node.kind != 91 || node.children.size > 64) return emptyList()
        val seen = HashSet<String>()
        return node.children.mapNotNull { entry ->
            if (entry.kind != 123) return@mapNotNull null
            val token = fields.text(entry, "token") ?: return@mapNotNull null
            val count = fields.optionalUnsigned(entry, "count") ?: return@mapNotNull null
            if (!reactionToken(token) || count !in 1..10_000 || !seen.add(token)) null
            else TimelineReaction(token, count.toInt())
        }
    }

    private fun receipts(fields: DisplayFields, row: ByteJson.Node): List<TimelineReceipt> {
        val node = fields.field(row, "receipts") ?: return emptyList()
        if (node.kind == 110 || node.kind != 91) return emptyList()
        return node.children.mapNotNull { entry ->
            if (entry.kind != 123) return@mapNotNull null
            val device = fields.text(entry, "device_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val state = fields.text(entry, "state") ?: return@mapNotNull null
            if (state !in RECEIPT_STATES || device.toByteArray(Charsets.UTF_8).size > 256) null
            else TimelineReceipt(device, state)
        }
    }

    private fun partial(fields: DisplayFields): Boolean {
        val node = fields.field(fields.root, "deliveries") ?: return false
        if (node.kind != 91) return false
        var submitted = false
        var failed = false
        for (entry in node.children) {
            if (entry.kind != 123) continue
            when (fields.text(entry, "state")) {
                "submitted" -> submitted = true
                "failed" -> failed = true
            }
        }
        return submitted && failed
    }

    private fun findTyping(fields: DisplayFields, node: ByteJson.Node, depth: Int): ByteJson.Node? {
        if (depth > 8 || node.kind != 123) return null
        fields.field(node, "typing")?.takeIf { it.kind == 123 }?.let { return it }
        var index = 1
        while (index < node.children.size) {
            findTyping(fields, node.children[index], depth + 1)?.let { return it }
            index += 2
        }
        return null
    }

    /** `hlc_ms` is milliseconds. A present value that is not a millisecond integer fails the page. */
    private fun displaySeconds(fields: DisplayFields, row: ByteJson.Node): Long? {
        val value = fields.field(row, "hlc_ms") ?: return null
        if (value.kind == 110) return null
        if (value.kind !in 48..57) throw IllegalArgumentException("hlc_ms")
        val raw = fields.optionalUnsigned(row, "hlc_ms") ?: throw IllegalArgumentException("hlc_ms")
        if (raw < 0 || raw > MAX_MS) throw IllegalArgumentException("hlc_ms")
        val seconds = raw / 1000
        if (seconds !in 0..MAX_SECONDS) throw IllegalArgumentException("hlc_ms")
        return seconds
    }

    /** The bus event carries the update in `body`. Identity is not a root field of that frame. */
    private fun conversationOf(fields: DisplayFields): String? {
        val body = fields.field(fields.root, "body")?.takeIf { it.kind == 123 } ?: return null
        val identity = fields.field(body, "identity")?.takeIf { it.kind == 123 } ?: return null
        val raw = fields.text(identity, "conversationId") ?: return null
        return raw.takeIf { it.isNotEmpty() && it.toByteArray(Charsets.UTF_8).size <= 256 }
    }

    private fun expiresMillis(fields: DisplayFields, typing: ByteJson.Node): Long {
        val seconds = fields.optionalUnsigned(typing, "expiresAt")
            ?: fields.optionalUnsigned(typing, "expires_at")
            ?: return 0L
        if (seconds <= 0 || seconds > MAX_SECONDS) return 0L
        return seconds * 1000
    }

    internal fun normalize(value: String) = value.replace('+', '-').replace('/', '_').replace("=", "")

    private fun reactionToken(token: String): Boolean {
        if (token.isEmpty() || token.toByteArray(Charsets.UTF_8).size > 64) return false
        if (token.any { it.isWhitespace() || it.isISOControl() }) return false
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(token)
        var count = 0
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            count += 1
            if (count > 1) return false
            end = iterator.next()
        }
        return count == 1
    }
}
