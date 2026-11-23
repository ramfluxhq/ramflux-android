// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/**
 * [state] is the SDK's comparison result (`unverified`, `verified`, `changed`, `blocked_by_policy`).
 * [expected] is the SDK's public compare-and-set stamp (hashes and heads only), kept verbatim so that
 * `contact.verify` records exactly the material the person compared; null when the SDK offers none.
 */
data class ContactSafetyMetadata(val root: String, val number: String, val fingerprint: String, val state: String = "unverified", val expected: String? = null) {
    override fun toString() = "ContactSafetyMetadata(<redacted>)"
}
object ContactSafetyMetadataReader {
    /** Public comparison material only. No signing keys, capabilities or unrelated reply fields. */
    fun read(reply: SecretReply): CoreResult<ContactSafetyMetadata> = try {
        reply.useOnce { raw ->
            val fields = DisplayFields(raw)
            val groups = fields.texts(fields.root, "safety_number", 64)
            require(groups.all { group -> group.all { it in '0'..'9' } })
            val fingerprint = requireNotNull(fields.text(fields.root, "fingerprint_hex", true))
            require(fingerprint.length in 1..256 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            val state = fields.text(fields.root, "verification_state") ?: "unverified"
            require(state.length <= 64 && state.all { it in 'a'..'z' || it == '_' })
            val expected = fields.field(fields.root, "expected")?.takeIf { it.kind != 110 }?.let { node ->
                require(node.kind == 123)
                fields.raw(node).also { require(it.length <= 4096) }
            }
            CoreResult.Success(
                ContactSafetyMetadata(
                    requireNotNull(fields.text(fields.root, "contact_identity_commitment", true)), groups.joinToString(" "), fingerprint, state, expected
                )
            )
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
