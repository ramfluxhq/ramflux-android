// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ContactCompletionMetadataTest {
    @Test fun acknowledgementMustMatchActualDecisionAndWipesReplyOnBothPaths() {
        for (request in listOf("request", "wrong-request")) {
            val raw = """{"request_id":"request","principal_id":"peer","direction":"inbound","state":"accepted","root_commitment":"must-not-copy"}""".toByteArray()
            val result = ContactCompletionMetadataReader.decision(SecretReply(SecretBytes.take(raw)), request, "peer", "accepted")
            assertEquals(request == "request", result is CoreResult.Success)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
}
