// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class ContactDisplayEntry(val id: String, val principal: String, val name: String?, val relation: String, val blocked: Boolean, val verified: Boolean, val root: String? = null) {
    override fun toString() = "ContactDisplayEntry(<redacted>)"
}
data class ContactRequestDisplayEntry(val id: String, val principal: String, val name: String?, val direction: String, val state: String, val note: String?, val count: ULong, val expiresAt: Long, val root: String? = null) {
    override fun toString() = "ContactRequestDisplayEntry(<redacted>)"
}
object ContactDirectoryMetadataReader {
    /**
     * `contact.list` and `contact.requests.list` are unpaged. A row is far above 100 bytes, so one 1 MiB
     * frame (the [ByteJson] ceiling) runs out before this bound; it only guards the id uniqueness check.
     */
    const val MAX_ROWS = 10_000

    suspend fun contacts(reply: SecretReply, dispatcher: CoroutineDispatcher = Dispatchers.Default): CoreResult<List<ContactDisplayEntry>> = ListReplyDecoder.decode(reply, dispatcher) { fields, chunks ->
        chunks.map(fields.rows("contacts", MAX_ROWS)) { row ->
            ContactDisplayEntry(requireNotNull(fields.text(row, "link_id", true)), requireNotNull(fields.text(row, "principal_id", true)),
                fields.text(row, "display_name"), requireNotNull(fields.text(row, "state", true)),
                fields.boolean(row, "blocked"), fields.boolean(row, "verified"), fields.text(row, "root_commitment"))
        }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
    }
    suspend fun requests(reply: SecretReply, dispatcher: CoroutineDispatcher = Dispatchers.Default): CoreResult<List<ContactRequestDisplayEntry>> = ListReplyDecoder.decode(reply, dispatcher) { fields, chunks ->
        chunks.map(fields.rows("requests", MAX_ROWS)) { row ->
            val direction = requireNotNull(fields.text(row, "direction", true))
            require(direction in setOf("inbound", "outbound"))
            ContactRequestDisplayEntry(requireNotNull(fields.text(row, "request_id", true)), requireNotNull(fields.text(row, "principal_id", true)),
                fields.text(row, "display_name"), direction, requireNotNull(fields.text(row, "state", true)), fields.text(row, "note"),
                fields.unsigned(row, "message_count"), requireNotNull(fields.optionalSigned(row, "expires_at")), fields.text(row, "root_commitment"))
        }.also { rows -> require(rows.map { it.id to it.direction }.distinct().size == rows.size) }
    }
}
