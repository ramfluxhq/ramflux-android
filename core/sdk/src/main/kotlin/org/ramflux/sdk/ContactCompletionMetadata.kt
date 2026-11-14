// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

object ContactCompletionMetadataReader {
    /** Successful ABI status alone is insufficient: bind the acknowledged decision to its actual target. */
    fun decision(reply: SecretReply, request: String, principal: String, state: String): CoreResult<Unit> = consume(reply) { fields ->
        require(fields.text(fields.root, "request_id", true) == request)
        require(fields.text(fields.root, "principal_id", true) == principal)
        require(fields.text(fields.root, "direction", true) == "inbound")
        require(fields.text(fields.root, "state", true) == state)
    }
    fun added(reply: SecretReply, principal: String?): CoreResult<Unit> = consume(reply) { fields ->
        val contact = requireNotNull(fields.field(fields.root, "contact"))
        val actual = requireNotNull(fields.text(contact, "principal_id", true))
        require(principal == null || actual == principal)
        require(fields.text(contact, "request_state", true) in setOf("pending", "accepted"))
    }
    private fun consume(reply: SecretReply, block: (DisplayFields) -> Unit): CoreResult<Unit> = try {
        reply.useOnce { block(DisplayFields(it)); CoreResult.Success(Unit) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
