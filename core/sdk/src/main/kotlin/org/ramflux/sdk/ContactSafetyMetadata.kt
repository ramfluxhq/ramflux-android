// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class ContactSafetyMetadata(val root: String, val number: String, val fingerprint: String) {
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
            CoreResult.Success(ContactSafetyMetadata(requireNotNull(fields.text(fields.root, "contact_identity_commitment", true)), groups.joinToString(" "), fingerprint))
        }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
