// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class ConversationDirectoryMetadataTest {
    @Test fun exactUnsignedCountsAndOpaqueCursorArePreservedWithoutRoutingSecrets() {
        val raw = """{"conversations":[{"id":"c","title":"Team","unread_count":18446744073709551615,"is_unread":true,"last_activity":1700000000,"archived":false,"hidden":false,"direct_route":{"key":"do-not-copy"}}],"next_cursor":"opaque"}""".toByteArray()
        val result = ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success
        assertEquals(ULong.MAX_VALUE, result.value.rows.single().unread); assertEquals("opaque", result.value.nextCursor)
        assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun malformedCountsAndMissingRowsAreTypedFailuresAndWiped() {
        for (count in listOf("-1", "1.5", "18446744073709551616")) {
            val raw = """{"conversations":[{"id":"c","unread_count":$count,"is_unread":true,"last_activity":1700000000,"archived":false,"hidden":false}],"next_cursor":null}""".toByteArray()
            assertTrue(ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) is CoreResult.Failure)
            assertTrue(raw.all { it == 0.toByte() })
        }
    }

    @Test fun manualUnreadAndSignedActivityAreIndependentOfNumericCount() {
        val raw = """{"conversations":[{"id":"manual","unread_count":0,"is_unread":true,"last_activity":-1,"archived":false,"hidden":false}],"next_cursor":null}""".toByteArray()
        val row = (ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value.rows.single()
        assertEquals(0uL, row.unread); assertTrue(row.isUnread); assertEquals(-1L, row.lastActivity)
        assertTrue(raw.all { it == 0.toByte() })
    }

    @Test fun kindAndParticipantsAreKeptAndRoutingSecretsStayOut() {
        val raw = """{"conversations":[{"id":"c","unread_count":0,"is_unread":false,"archived":false,"hidden":false,"kind":"direct","participants":["self","peer"],"direct_route":{"key":"do-not-copy"}}],"next_cursor":null}""".toByteArray()
        val row = (ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value.rows.single()
        assertEquals("direct", row.kind)
        assertEquals(listOf("self", "peer"), row.participants)
        assertFalse(row.toString().contains("do-not-copy"))
        assertTrue(raw.all { it == 0.toByte() })
    }

    @Test fun aWideOrOverlongParticipantListDoesNotDropThePage() {
        val wide = List(33) { "\"${"p".repeat(200)}\"" }.joinToString(",")
        val exact = "r".repeat(256)
        val over = "q".repeat(257)
        val raw = buildString {
            append("""{"conversations":[""")
            append("""{"id":"wide","unread_count":0,"is_unread":false,"archived":false,"hidden":false,"participants":[""")
            append(wide).append("]},")
            append("""{"id":"exact","unread_count":0,"is_unread":false,"archived":false,"hidden":false,"participants":["self","""")
            append(exact).append(""""]},""")
            append("""{"id":"over","unread_count":0,"is_unread":false,"archived":false,"hidden":false,"participants":["","""")
            append(over).append(""""]},""")
            append("""{"id":"plain","unread_count":1,"is_unread":true,"archived":false,"hidden":false}""")
            append("""],"next_cursor":null}""")
        }.toByteArray()
        val rows = (ConversationDirectoryMetadataReader.read(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value.rows
        assertEquals(listOf("wide", "exact", "over", "plain"), rows.map { it.id })
        assertEquals(33, rows[0].participants.size)
        assertEquals(200, rows[0].participants[0].length)
        assertEquals(listOf("self", exact), rows[1].participants)
        assertTrue(rows[2].participants.isEmpty())
        assertTrue(rows[3].participants.isEmpty())
        assertTrue(raw.all { it == 0.toByte() })
    }

    @Test fun changeEventsCarryOneRowAndRemovalIsHidden() {
        fun change(text: String) = ConversationDirectoryMetadataReader.change(SecretReply(SecretBytes.take(text.toByteArray()))).row
        val row = """{"id":"c1","title":"T","archived":false,"hidden":false,"last_activity":42,"unread_count":3,"is_unread":true,"muted":true}"""
        val changed = change("""{"method":"conversation.changed","body":$row}""")
        assertEquals("c1", changed?.id)
        assertEquals(3uL, changed?.unread)
        assertEquals(42L, changed?.lastActivity)
        assertTrue(changed?.muted == true)
        val received = change("""{"method":"message.received","body":{"message":{"id":"m"},"conversation":$row}}""")
        assertEquals("c1", received?.id)
        val removed = change("""{"method":"conversation.changed","body":{"id":"c2","removed":true}}""")
        assertTrue(removed?.hidden == true)
        assertNull(change("""{"method":"contact.changed","body":$row}"""))
        assertNull(change("""{"method":"conversation.changed","body":{"id":"c3"}}"""))
        val request = ConversationDirectoryMetadataReader.change(SecretReply(SecretBytes.take("""{"method":"contact.requested","body":{}}""".toByteArray())))
        assertEquals("contact.requested", request.topic)
        assertNull(request.row)
    }
}
