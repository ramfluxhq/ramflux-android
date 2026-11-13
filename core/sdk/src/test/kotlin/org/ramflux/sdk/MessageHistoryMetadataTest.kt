// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class MessageHistoryMetadataTest {
    @Test fun publishedMixedTimelinePreservesOrderAndCallHasNoInventedMessageFlags() {
        val raw = """{"messages":[{"message_id":"one","conversation_id":"c","created_at":10,"is_own":false,"deleted":false,"body_utf8":"approved"},{"message_id":"call","conversation_id":"c","kind":"call","created_at":11,"call":{"call_id":"call"},"duration_seconds":null,"missed":true},{"message_id":"deleted","conversation_id":"c","created_at":12,"is_own":true,"deleted":true,"body_utf8":"must-hide"}]}""".toByteArray()
        val rows = (MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(listOf("one", "call", "deleted"), rows.map { it.id })
        assertEquals(listOf(10L, 11L, 12L), rows.map { it.createdAt })
        assertTrue(rows[1] is CallHistoryEntry)
        assertNull((rows[2] as MessageHistoryEntry).text)
        assertTrue(raw.all { it == 0.toByte() })
    }

    @Test fun onlyApprovedBodyIsReadAndDeletedRowsNeverDiscloseSenderOrContent() {
        val raw = """{"messages":[{"message_id":"one","conversation_id":"c","is_own":false,"deleted":false,"sender_id":"peer","body_utf8":"approved","encrypted_body":"ciphertext"},{"message_id":"two","conversation_id":"c","is_own":true,"deleted":true,"sender_id":"must-hide","body_utf8":"must-hide"}]}""".toByteArray()
        val rows = (MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals("approved", (rows.first() as MessageHistoryEntry).text); assertNull((rows.last() as MessageHistoryEntry).text); assertNull((rows.last() as MessageHistoryEntry).sender)
        assertFalse(rows.toString().contains("approved")); assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun missingBodyIsUnavailableAndDuplicateMessageIdsFailClosed() {
        val row = """{"message_id":"m","conversation_id":"c","is_own":false,"deleted":false,"encrypted_body":"must-not-decode"}"""
        val valid = """{"messages":[$row]}""".toByteArray()
        assertNull((MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(valid))) as CoreResult.Success).value.single().let { (it as MessageHistoryEntry).text })
        val invalid = """{"messages":[$row,$row]}""".toByteArray()
        assertTrue(MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(invalid))) is CoreResult.Failure)
        assertTrue(invalid.all { it == 0.toByte() })
    }
}
