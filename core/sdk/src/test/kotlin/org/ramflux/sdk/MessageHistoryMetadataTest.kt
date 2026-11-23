// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test

class MessageHistoryMetadataTest {
    @Test fun publishedMixedTimelinePreservesOrderAndConvertsDisplayTime() {
        val raw = page(
            message("one", 10_000, """"text":"approved","author":{"device_id":"peer"}"""),
            call(11_000),
            tombstone(12_000),
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
    }

    @Test fun transientEventUsesBodyConversationAndFiltersAccountGeneration() {
        val now = 1_700_000_000_000L
        val shown = typingOf("acct", 7u, "c", active = true, expiresAt = "1700000005", now)
        assertEquals("c", shown?.conversationId)
        assertEquals(true, shown?.active)
        assertEquals(1_700_000_005_000L, shown?.expiresAtMs)
        assertNull(filtered("acct", 7u, "other", 7u))
        assertNull(filtered("acct", 7u, "acct", 8u))
        assertEquals(false, typingOf("acct", 7u, "c", active = null, expiresAt = "1700000005", now)?.active)
        assertEquals(false, typingOf("acct", 7u, "c", active = true, expiresAt = "1699999999", now)?.active)
        assertEquals("other", typingOf("acct", 7u, "other", active = true, expiresAt = "1700000005", now)?.conversationId)
        val guessed = event("acct", "7", update("c", true, "1700000005"), "guess")
        val accepted = ReplyCodec.decodeEvent(NativeReply(0, guessed), "acct", 7u) as CoreResult.Success
        val secret = checkNotNull(accepted.value)
        assertEquals("c", MessageHistoryMetadataReader.peerTyping(secret, now)?.conversationId)
        val bare = update("c", true, "1700000005").toByteArray()
        assertNull(MessageHistoryMetadataReader.peerTyping(SecretReply(SecretBytes.take(bare)), now))
        val message = event("acct", "7", """{"identity":{"conversationId":"c"},"content":{"message":{"text":"hi"}}}""")
        val delivered = ReplyCodec.decodeEvent(NativeReply(0, message), "acct", 7u) as CoreResult.Success
        assertNull(MessageHistoryMetadataReader.peerTyping(checkNotNull(delivered.value), now))
    }

    @Test fun earlyEpochMillisStayMillisecondsAndAQuotedTimestampFailsClosed() {
        val early = page(message("one", 1000, """"text":"approved","author":{"device_id":"peer"}"""))
        assertEquals(1L, read(early).single().createdAt)
        val quoted = (
            """{"schema":"ramflux.message.read_model.v1","conversation_id":"c","messages":[""" +
                """{"id":"one","conversation_id":"c","kind":"message","state":"content","hlc_ms":"1000","text":"x"}]}"""
            ).toByteArray()
        assertTrue(MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(quoted))) is CoreResult.Failure)
        assertTrue(quoted.all { it == 0.toByte() })
        val huge = page(message("one", 253_402_300_799_001, """"text":"approved""""))
        assertTrue(MessageHistoryMetadataReader.read(SecretReply(SecretBytes.take(huge))) is CoreResult.Failure)
    }

    @Test fun pageReturnsTheCursorAndBoundedAttachmentReferences() {
        val thumb = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val media = (0 until 20).joinToString(",") { index ->
            if (index == 0) {
                """{"mimeType":"image/png","size":"2048","fileName":"a.png",""" +
                    """"inlineThumbnail":{"mimeType":"image/png","width":4,"height":3,"encodedImage":"$thumb"},""" +
                    """"largeManifest":{"expiresAt":"1700000000"}}"""
            } else {
                """{"mime_type":"application/pdf","size":7,"file_name":"f$index.pdf"}"""
            }
        }
        val raw = (
            """{"schema":"ramflux.message.read_model.v1","conversation_id":"c","next_before":{"hlc_ms":"9","id":"x"},""" +
                """"messages":[${message("one", 10_000, """"content":{"message":{"text":"hi","media":[$media]}}""")},""" +
                """${tombstone(11_000)}]}"""
            ).toByteArray()
        val page = (MessageHistoryMetadataReader.page(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals("""{"hlc_ms":"9","id":"x"}""", page.nextBefore)
        val first = page.rows[0] as MessageHistoryEntry
        assertEquals(MessageHistoryMetadataReader.MAX_ATTACHMENTS, first.attachments.size)
        assertEquals("image/png", first.attachments[0].mimeType)
        assertEquals(2048L, first.attachments[0].size)
        assertEquals(1_700_000_000L, first.attachments[0].expiresAt)
        assertEquals(4, first.attachments[0].thumbnail?.width)
        assertEquals("f1.pdf", first.attachments[1].fileName)
        assertEquals(7L, first.attachments[1].size)
        assertTrue((page.rows[1] as MessageHistoryEntry).attachments.isEmpty())
        assertTrue(raw.all { it == 0.toByte() })
        val last = page("""{"id":"z","conversation_id":"c","kind":"message","state":"content","hlc_ms":1,"text":"t"}""")
        assertNull((MessageHistoryMetadataReader.page(SecretReply(SecretBytes.take(last))) as CoreResult.Success).value.nextBefore)
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

    private fun typingOf(
        account: String,
        generation: ULong,
        conversation: String,
        active: Boolean?,
        expiresAt: String?,
        now: Long,
    ): PeerTyping? {
        val decoded = ReplyCodec.decodeEvent(
            NativeReply(0, event(account, generation.toString(), update(conversation, active, expiresAt))),
            account,
            generation
        )
        val secret = (decoded as CoreResult.Success).value
        return MessageHistoryMetadataReader.peerTyping(checkNotNull(secret), now)
    }

    private fun filtered(account: String, generation: ULong, caller: String, callerGeneration: ULong): SecretReply? {
        val decoded = ReplyCodec.decodeEvent(
            NativeReply(0, event(account, generation.toString(), update("c", true, "1700000005"))),
            caller,
            callerGeneration
        )
        return (decoded as CoreResult.Success).value
    }

    private fun update(conversation: String, active: Boolean?, expiresAt: String?): String {
        val fields = ArrayList<String>()
        if (active != null) fields.add("\"active\":$active")
        if (expiresAt != null) fields.add("\"expiresAt\":\"$expiresAt\"")
        return """{"identity":{"conversationId":"$conversation"},"content":{"typing":{${fields.joinToString(",")}}}}"""
    }

    private fun event(account: String, generation: String, body: String, rootConversation: String? = null): ByteArray {
        val extra = if (rootConversation == null) "" else ""","conversationId":"$rootConversation""""
        return (
            """{"bus_protocol":"ramflux.local_bus.v1","frame_id":"transient","kind":"event","request_id":"",""" +
                """"account_id":"$account","sdk_api":"message","method":"message.transient","body":$body$extra,""" +
                """"subscription_generation":"$generation"}"""
            ).toByteArray()
    }
}
