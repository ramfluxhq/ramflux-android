// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ContactDirectoryMetadataTest {
    @Test fun narrowContactFactsIgnoreRoutingAndIdentitySecretsAndWipeBytes() {
        val raw = """{"contacts":[{"link_id":"link","principal_id":"peer","state":"accepted","blocked":false,"verified":false,"display_name":"Name","root_commitment":"public-root","capabilities":{"credential":"must-not-copy"}}]}""".toByteArray()
        val rows = (ContactDirectoryMetadataReader.contacts(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(ContactDisplayEntry("link", "peer", "Name", "accepted", false, false, "public-root"), rows.single())
        assertFalse(rows.toString().contains("must-not-copy"))
        assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun publishedRequestShapePreservesDirectionStateExactCountAndExpiry() {
        val raw = """{"requests":[{"request_id":"request","principal_id":"peer","display_name":null,"direction":"inbound","state":"pending","note":"Peer note","message_count":18446744073709551615,"expires_at":1700000000,"preview":{"encrypted_body":"must-not-copy"},"root_commitment":"public-root","safety_number":"must-not-copy"}],"authority_floors":{"token":"must-not-copy"}}""".toByteArray()
        val row = (ContactDirectoryMetadataReader.requests(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value.single()
        assertEquals(ULong.MAX_VALUE, row.count)
        assertEquals(1700000000L, row.expiresAt)
        assertEquals("inbound", row.direction)
        assertEquals("Peer note", row.note)
        assertFalse(row.toString().contains("Peer note"))
        assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun missingFactsAndDuplicateIdsFailInsteadOfInventingEmptyList() {
        val row = """{"link_id":"link","principal_id":"peer","state":"accepted","blocked":false,"verified":false}"""
        for (body in listOf("{}", """{"contacts":[$row,$row]}""", """{"contacts":[{"link_id":"link"}]}""")) {
            val raw = body.toByteArray()
            assertTrue(ContactDirectoryMetadataReader.contacts(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
}
