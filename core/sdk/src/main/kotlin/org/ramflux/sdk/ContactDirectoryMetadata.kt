// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class ContactDisplayEntry(val id: String, val principal: String, val name: String?, val relation: String, val blocked: Boolean, val verified: Boolean, val root: String? = null) {
    override fun toString() = "ContactDisplayEntry(<redacted>)"
}
data class ContactRequestDisplayEntry(val id: String, val principal: String, val name: String?, val direction: String, val state: String, val note: String?, val count: ULong, val expiresAt: Long, val root: String? = null) {
    override fun toString() = "ContactRequestDisplayEntry(<redacted>)"
}
object ContactDirectoryMetadataReader {
    fun contacts(reply: SecretReply): CoreResult<List<ContactDisplayEntry>> = decode(reply) { fields ->
        fields.rows("contacts", 1000).map { row ->
            ContactDisplayEntry(requireNotNull(fields.text(row, "link_id", true)), requireNotNull(fields.text(row, "principal_id", true)),
                fields.text(row, "display_name"), requireNotNull(fields.text(row, "state", true)),
                fields.boolean(row, "blocked"), fields.boolean(row, "verified"), fields.text(row, "root_commitment"))
        }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) }
    }
    fun requests(reply: SecretReply): CoreResult<List<ContactRequestDisplayEntry>> = decode(reply) { fields ->
        fields.rows("requests", 1000).map { row ->
            val direction = requireNotNull(fields.text(row, "direction", true))
            require(direction in setOf("inbound", "outbound"))
            ContactRequestDisplayEntry(requireNotNull(fields.text(row, "request_id", true)), requireNotNull(fields.text(row, "principal_id", true)),
                fields.text(row, "display_name"), direction, requireNotNull(fields.text(row, "state", true)), fields.text(row, "note"),
                fields.unsigned(row, "message_count"), requireNotNull(fields.optionalSigned(row, "expires_at")), fields.text(row, "root_commitment"))
        }.also { rows -> require(rows.map { it.id to it.direction }.distinct().size == rows.size) }
    }
    private fun <T> decode(reply: SecretReply, block: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(block(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
