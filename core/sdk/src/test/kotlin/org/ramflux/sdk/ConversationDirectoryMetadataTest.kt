// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ConversationDirectoryMetadataTest {
    @Test fun exactUnsignedCountsAndOpaqueCursorArePreservedWithoutRoutingSecrets() {
        val raw = """{"conversations":[{"id":"c","title":"Team","unread_count":18446744073709551615,"archived":false,"hidden":false,"direct_route":{"key":"do-not-copy"}}],"next_cursor":"opaque"}""".toByteArray()
        val result = ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success
        assertEquals(ULong.MAX_VALUE, result.value.rows.single().unread); assertEquals("opaque", result.value.nextCursor)
        assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun malformedCountsAndMissingRowsAreTypedFailuresAndWiped() {
        for (count in listOf("-1", "1.5", "18446744073709551616")) {
            val raw = """{"conversations":[{"id":"c","unread_count":$count,"archived":false,"hidden":false}],"next_cursor":null}""".toByteArray()
            assertTrue(ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }
}
