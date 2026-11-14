// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class CallDisplay(val id: String, val conversation: String, val peer: String, val direction: String, val state: String,
    val sequence: ULong, val generation: ULong, val created: Long, val answered: Long?, val ended: Long?,
    val endReason: String?, val failure: String?, val peerReason: String?) {
    override fun toString() = "CallDisplay(<redacted>)"
}
object CallMetadataReader {
    fun list(reply: SecretReply) = decode(reply) { f -> f.rows("calls", 1000).map { row -> record(f, row) }.also { rows ->
        require(rows.map { it.id }.distinct().size == rows.size)
    } }
    fun record(reply: SecretReply) = decode(reply) { record(it, it.root) }
    private fun record(f: DisplayFields, row: ByteJson.Node): CallDisplay {
        fun text(key: String) = requireNotNull(f.text(row, key, true))
        fun time(key: String): Long? = f.optionalSigned(row, key)?.also { require(it in 0L..253402300799L) }
        return CallDisplay(text("call_id"), text("conversation_id"), text("peer_id"), text("direction"), text("state"),
            f.unsigned(row, "seq"), f.unsigned(row, "media_generation"), requireNotNull(time("created_at")), time("answered_at"), time("ended_at"),
            f.text(row, "end_reason"), f.text(row, "failure_reason"), f.text(row, "peer_reason"))
    }
    private fun <T> decode(reply: SecretReply, body: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(body(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
