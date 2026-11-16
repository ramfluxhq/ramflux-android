// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test

class MessageHistoryMetadataTest {
    @Test fun publishedMixedTimelinePreservesOrderAndConvertsDisplayTime() {
        val raw = page(
            message("one", 10, """"text":"approved","author":{"device_id":"peer"}"""),
            call(11),
            tombstone(12),
        )
        val rows = read(raw)
        assertEquals(listOf("one", "call", "deleted"), rows.map { it.id })
        assertEquals(listOf(10L, 11L, 12L), rows.map { it.createdAt })
        assertTrue(rows[1] is CallHistoryEntry)
        assertNull((rows[2] as MessageHistoryEntry).text)
        assertNull((rows[2] as MessageHistoryEntry).sender)
        assertTrue(raw.all { it == 0.toByte() })
    }

    @Test fun millisTimestampBecomesEpochSecondsAndUnknownKindStaysOnThePage() {
        val raw = page(
            message(
                "one",
                1_700_000_000_000,
                """"text":"approved","device_sequence":"00000000000000000001","author":{"device_id":"peer"}""",
            ),
            """{"id":"future","conversation_id":"c","kind":"future-kind","hlc_ms":10,"text":"hidden-kind"}""",
        )
        val rows = read(raw)
        assertEquals(1_700_000_000L, rows.first().createdAt)
        val placeholder = rows.last() as MessageHistoryEntry
        assertTrue(placeholder.placeholder)
        assertNull(placeholder.text)
        assertFalse(rows.toString().contains("hidden-kind"))
        assertFalse(rows.toString().contains("approved"))
    }

    @Test fun serviceEditReplyReactionAndReceiptRenderWithoutHiddenRows() {
        val raw = page(
            """{"id":"edit-1","conversation_id":"c","kind":"edit","revision":2,""" +
                """"content":{"edit":{"target":{"id":"one"},"revision":"2","replacement":{"text":"edited"}}}}""",
            message(
                "one",
                10,
                """"is_own":true,"text":"old","revision":0,"author":{"device_id":"self"},""" +
                    """"reactions":[{"token":"+","count":1}],"receipts":[{"device_id":"self","state":"read"}]""",
            ),
            message(
                "two",
                11,
                """"text":"reply","author":{"device_id":"peer"},"content":{"message":{"text":"reply","replyTo":{"id":"one"}}}""",
            ),
            """{"id":"svc","conversation_id":"c","kind":"service","state":"content","hlc_ms":12,"text":"member joined"}""",
        )
        val rows = read(raw)
        assertEquals(listOf("one", "two", "svc"), rows.map { it.id })
        val edited = rows[0] as MessageHistoryEntry
        assertEquals("edited", edited.text)
        assertTrue(edited.edited)
        assertEquals("+", edited.reactions.single().token)
        assertEquals("read", edited.receipts.single().state)
        val reply = rows[1] as MessageHistoryEntry
        assertEquals("edited", reply.replyPreview)
        assertEquals("member joined", (rows[2] as MessageHistoryEntry).text)
        assertTrue((rows[2] as MessageHistoryEntry).service)
    }

    @Test fun erasureAndBadSequencePlaceholderDoNotFailAValidSibling() {
        val raw = page(
            message("one", 10, """"text":"approved","author":{"device_id":"peer"}"""),
            """{"id":"gone","conversation_id":"c","kind":"message","state":"content","hlc_ms":11,""" +
                """"erasure":{"scope":"everyone"},"text":"must-hide","author":{"device_id":"must-hide"}}""",
            """{"id":"bad-seq","conversation_id":"c","kind":"message","state":"content",""" +
                """"hlc_ms":12,"device_sequence":1,"text":"must-hide"}""",
        )
        val rows = read(raw)
        assertEquals("approved", (rows[0] as MessageHistoryEntry).text)
        assertTrue((rows[1] as MessageHistoryEntry).deleted)
        assertNull((rows[1] as MessageHistoryEntry).sender)
        assertTrue((rows[2] as MessageHistoryEntry).placeholder)
        assertNull((rows[2] as MessageHistoryEntry).text)
    }

    @Test fun badSchemaAndDuplicateNormalizedIdsFailClosedAndZeroize() {
        val schema = """{"schema":"other","conversation_id":"c","messages":[]}""".toByteArray()
        assertTrue(MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(schema))) is CoreResult.Failure)
        assertTrue(schema.all { it == 0.toByte() })
        val invalid = page(
            message("ab+c", 10, """"text":"approved""""),
            message("ab-c", 11, """"text":"other""""),
        )
        assertTrue(MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(invalid))) is CoreResult.Failure)
        assertTrue(invalid.all { it == 0.toByte() })
    }

    @Test fun deliveryRecognizesPartialAndTypingDoesNotFail() {
        val partial = (
            """{"message_id":"m","conversation_id":"c","delivery_state":"submitted",""" +
                """"deliveries":[{"state":"submitted"},{"state":"failed"}]}"""
            ).toByteArray()
        val decoded = MessageHistoryMetadataReader.delivery(SecretReply(SecretBytes.take(partial)), "c", "m")
        val delivery = (decoded as CoreResult.Success).value
        assertTrue(delivery.partial)
        assertTrue(partial.all { it == 0.toByte() })
        val frame = """{"send":{"content":{"typing":{"active":true}}}}""".toByteArray()
        assertEquals(true, MessageHistoryMetadataReader.peerTyping(SecretReply(SecretBytes.take(frame))))
        assertTrue(frame.all { it == 0.toByte() })
        val other = """{"kind":"message"}""".toByteArray()
        assertNull(MessageHistoryMetadataReader.peerTyping(SecretReply(SecretBytes.take(other))))
    }

    private fun read(raw: ByteArray) =
        (MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value

    private fun page(vararg rows: String) =
        """{"schema":"ramflux.message.read_model.v1","conversation_id":"c","messages":[${rows.joinToString(",")}]}""".toByteArray()

    private fun message(id: String, hlc: Long, extra: String) =
        """{"id":"$id","conversation_id":"c","kind":"message","state":"content","hlc_ms":$hlc,$extra}"""

    private fun call(hlc: Long) = """{"id":"call","conversation_id":"c","kind":"call","hlc_ms":$hlc}"""

    private fun tombstone(hlc: Long) =
        """{"id":"deleted","conversation_id":"c","kind":"message","state":"tombstone","is_own":true,""" +
            """"hlc_ms":$hlc,"text":"must-hide","author":{"device_id":"must-hide"}}"""
}
