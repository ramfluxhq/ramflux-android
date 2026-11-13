// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ContactCompletionMetadataTest {
    @Test fun actualSafetyMaterialIsNarrowPublicProjectionAndReplyBytesAreWiped() {
        val raw = """{"contact_identity_commitment":"root","safety_number":["1234","5678"],"fingerprint_hex":"abcdef","private_key":"must-not-copy"}""".toByteArray()
        val result = (ContactSafetyMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(ContactSafetyMetadata("root", "1234 5678", "abcdef"), result)
        assertTrue(raw.all { it == 0.toByte() })
        assertFalse(result.toString().contains("must-not-copy"))
    }

    @Test fun acknowledgementMustMatchActualDecisionAndWipesReplyOnBothPaths() {
        for (request in listOf("request", "wrong-request")) {
            val raw = """{"request_id":"request","principal_id":"peer","direction":"inbound","state":"accepted","root_commitment":"must-not-copy"}""".toByteArray()
            val result = ContactCompletionMetadataReader.decision(SecretReply(SecretBytes.take(raw)), request, "peer", "accepted")
            assertEquals(request == "request", result is CoreResult.Success)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
}
