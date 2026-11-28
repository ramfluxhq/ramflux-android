// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/**
 * What `contact.verification.status` reports. [state] is the SDK's `identity_state` (`verified`, `tofu`, `changed`); null when
 * the reply carries none. [pendingAnchor] and [previousAnchor] are present only while the state is `changed` and core reports
 * them. Anchors are public identity values and are never logged.
 */
data class ContactIdentityStatus(val state: String?, val pendingAnchor: String?, val previousAnchor: String?) {
    override fun toString() = "ContactIdentityStatus(<redacted>)"
}

/** The `contact.identity_changed` event payload. */
data class ContactIdentityEvent(
    val contact: String,
    val username: String?,
    val previousAnchor: String,
    val newAnchor: String,
    val aiFlag: Boolean,
) {
    override fun toString() = "ContactIdentityEvent(<redacted>)"
}

/** A parsed identity event frame. */
sealed interface ContactIdentitySignalMetadata {
    data class Changed(val event: ContactIdentityEvent) : ContactIdentitySignalMetadata
    data class Devices(val conversation: String?) : ContactIdentitySignalMetadata {
        override fun toString() = "Devices(<redacted>)"
    }
}

/** One peer device of `conversation.security`; [aiFlag] is the SDK-verified AI marker. */
data class SecurityDeviceMetadata(val aiFlag: Boolean)

/** The `conversation.security` reply: [state] is `ok`, `pending`, `re_established` or an unknown token; [warning] the warning code. */
data class ConversationSecurityMetadata(val state: String, val epoch: Long, val devices: List<SecurityDeviceMetadata>, val warning: String?) {
    override fun toString() = "ConversationSecurityMetadata(<redacted>)"
}

object ContactIdentityMetadataReader {
    private const val BOUND = 4096
    private const val DEVICES = 1024

    /** `contact.verification.status`: only the three identity fields are read. */
    fun status(reply: SecretReply): CoreResult<ContactIdentityStatus> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val state = token(fields.text(fields.root, "identity_state"))
            CoreResult.Success(ContactIdentityStatus(state, anchor(fields, fields.root, "pending_anchor"), anchor(fields, fields.root, "previous_anchor")))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }

    /**
     * One event frame of the identity topics, read once (the frame is wiped): `contact.identity_changed` or
     * `conversation.devices_changed`. The payload is in `body` (the frame root is the fallback). Any other topic, and a
     * payload that does not parse, gives null.
     */
    fun signal(reply: SecretReply): ContactIdentitySignalMetadata? = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val body = fields.field(fields.root, "body")?.takeIf { it.kind == 123 } ?: fields.root
            when (fields.text(fields.root, "method")?.takeIf { it.length <= 128 }) {
                "contact.identity_changed" -> {
                    val contact = requireNotNull(anchor(fields, body, "contact"))
                    val username = fields.text(body, "username")?.takeIf { it.isNotEmpty() && it.length <= 256 }
                    val previous = requireNotNull(anchor(fields, body, "previous_anchor"))
                    val next = requireNotNull(anchor(fields, body, "new_anchor"))
                    ContactIdentitySignalMetadata.Changed(
                        ContactIdentityEvent(contact, username, previous, next, fields.optionalBoolean(body, "ai_flag") ?: false)
                    )
                }
                "conversation.devices_changed" -> ContactIdentitySignalMetadata.Devices(anchor(fields, body, "conversation_id"))
                else -> null
            }
        }
    } catch (_: Exception) { null }
    finally { reply.close() }

    /** `conversation.security`: the state token, the epoch, the peer devices and the warning code. Anchors and device ids are not kept. */
    fun security(reply: SecretReply): CoreResult<ConversationSecurityMetadata> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val state = requireNotNull(token(fields.text(fields.root, "state")))
            val epoch = fields.optionalUnsigned(fields.root, "epoch") ?: 0L
            val members = fields.field(fields.root, "members")?.takeIf { it.kind == 91 }?.children.orEmpty()
            require(members.size <= DEVICES)
            val devices = members.map { SecurityDeviceMetadata(fields.optionalBoolean(it, "ai_flag") ?: false) }
            val warning = fields.field(fields.root, "warning")?.takeIf { it.kind == 123 }?.let { token(fields.text(it, "code")) }
            CoreResult.Success(ConversationSecurityMetadata(state, epoch, devices, warning))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }

    /** `contact.trust.accept_change`: only `state: accepted` confirms. */
    fun accepted(reply: SecretReply): Boolean = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            fields.text(fields.root, "state") == "accepted"
        }
    } catch (_: Exception) { false }
    finally { reply.close() }

    private fun anchor(fields: DisplayFields, row: ByteJson.Node, key: String): String? =
        fields.text(row, key)?.takeIf { it.isNotEmpty() && it.length <= BOUND }

    private fun token(value: String?): String? =
        value?.takeIf { it.length in 1..64 && it.all { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' } }
}
