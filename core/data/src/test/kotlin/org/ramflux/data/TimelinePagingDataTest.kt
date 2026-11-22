// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class TimelinePagingDataTest {
    @Test fun pagesBackwardsWithTheOpaqueNextBeforeCursor() = runTest(timeout = 5.seconds) {
        val cursor = """{"id":"AAA=","conversationId":"c","hlc":"12"}"""
        val abi = FakeAbi().apply {
            completionBody = page(cursor, message("two", 2_000, """"text":"newer""""), message("one", 1_000, """"text":"older""""))
        }
        val client = open(abi, "/test/paging")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val first = (repo.page("a", "c") as ClientResult.Ready).value
            assertEquals(listOf("one", "two"), first.rows.map { it.id })
            assertNotNull(first.older)
            assertNull(abi.submitted.last()["body"]?.jsonObject?.get("before_order_key"))
            abi.completionBody = page(null, message("zero", 500, """"text":"oldest""""))
            val second = (repo.page("a", "c", first.older) as ClientResult.Ready).value
            assertEquals(listOf("zero"), second.rows.map { it.id })
            assertNull(second.older)
            val sent = abi.submitted.last()["body"]?.jsonObject?.get("before_order_key")?.jsonObject
            assertEquals(Json.parseToJsonElement(cursor), sent)
            assertTrue(repo.page("a", "c", "not json") is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun mediaReferencesAreReadFromTheContentMessage() = runTest(timeout = 5.seconds) {
        val media = """"content":{"message":{"text":"caption","media":[""" +
            """{"objectId":"o1","mimeType":"image/jpeg","size":"2048","fileName":"photo.jpg",""" +
            """"inlineThumbnail":{"mimeType":"image/webp","width":40,"height":20,"encodedImage":"AQID"},""" +
            """"largeManifest":{"expiresAt":"1900000000"}},""" +
            """{"objectId":"o2","mimeType":"application/pdf","size":"10","fileName":"report.pdf"}]}}"""
        val abi = FakeAbi().apply {
            completionBody = page(null, message("m", 1_000, media), tombstone("gone", 2_000, media))
        }
        val client = open(abi, "/test/media")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val rows = (repo.page("a", "c") as ClientResult.Ready).value.rows
            val message = rows.first { it.id == "m" } as MessageSummary
            assertEquals("caption", message.text)
            assertEquals(2, message.attachments.size)
            val image = message.attachments[0]
            assertEquals(MediaKind.IMAGE, image.kind)
            assertEquals(2048L, image.size)
            assertEquals("photo.jpg", image.fileName)
            assertEquals(1_900_000_000L, image.availableUntil)
            assertEquals(2f, image.aspectRatio)
            assertEquals(1, message.attachments[1].index)
            assertEquals(MediaKind.FILE, message.attachments[1].kind)
            assertTrue((rows.first { it.id == "gone" } as MessageSummary).attachments.isEmpty())
        } finally { client.close() }
    }

    @Test fun markReadNamesTheLocalDeviceAsReader() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            onSubmitted = { frame ->
                completionBody = when (frame["method"]?.jsonPrimitive?.content) {
                    "device.list" -> Json.parseToJsonElement(
                        """{"devices":[{"device_id":"other","is_local":false,"device_epoch":1},{"device_id":"mine","is_local":true,"device_epoch":1}]}""",
                    ).jsonObject
                    else -> buildJsonObject { put("scope", "local_projection") }
                }
            }
        }
        val client = open(abi, "/test/read")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val result = repo.markRead("a", "c", "m")
            assertTrue(result.toString(), result is ClientResult.Ready)
            val frame = abi.submitted.last()
            assertEquals("message.receipt.read", frame["method"]?.jsonPrimitive?.content)
            assertEquals("m", frame["body"]?.jsonObject?.get("message_id")?.jsonPrimitive?.content)
            assertEquals("mine", frame["body"]?.jsonObject?.get("reader_id")?.jsonPrimitive?.content)
            assertNull(frame["body"]?.jsonObject?.get("target_delivery_id"))
            assertTrue(repo.markRead("a", "c", "") is ClientResult.Failed)
        } finally { client.close() }
    }

    @Test fun updatesSubscribeToNamedTopicsOnly() = runTest(timeout = 5.seconds) {
        val topics = DefaultMessageHistoryRepository.subscriptionTopics()
        assertFalse(topics.contains("*"))
        assertTrue(topics.containsAll(listOf("message.received", "message.updated", "message.transient")))
    }

    @Test fun forwardTargetsListVisibleDirectConversationsAndFollowTheCursor() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            completionBody = Json.parseToJsonElement(
                """{"conversations":[""" +
                    """{"id":"d","title":"Dana","kind":"direct","unread_count":0,"is_unread":false,"archived":false,"hidden":false},""" +
                    """{"id":"g","title":"Team","kind":"group","unread_count":0,"is_unread":false,"archived":false,"hidden":false},""" +
                    """{"id":"h","title":"Hidden","kind":"direct","unread_count":0,"is_unread":false,"archived":false,"hidden":true}""" +
                    """],"next_cursor":"page-2"}""",
            ).jsonObject
        }
        val client = open(abi, "/test/forward")
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val page = (repo.forwardTargets("a") as ClientResult.Ready).value
            assertEquals(listOf("d"), page.rows.map { it.conversation })
            assertNull(page.rows.single().group)
            assertEquals("page-2", page.next)
            repo.forwardTargets("a", page.next)
            assertEquals("page-2", abi.submitted.last()["body"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
            assertTrue(repo.forwardTargets("", null) is ClientResult.Failed)
        } finally { client.close() }
    }

    private suspend fun open(abi: FakeAbi, root: String) =
        (CoreClient.open(abi, root, "a".repeat(64)) as CoreResult.Success).value

    private fun page(cursor: String?, vararg rows: String) = Json.parseToJsonElement(
        """{"schema":"ramflux.message.read_model.v1","conversation_id":"c","messages":[${rows.joinToString(",")}],""" +
            """"next_before":${cursor ?: "null"},"snapshot_sequence":1}""",
    ).jsonObject

    private fun message(id: String, hlc: Long, extra: String) =
        """{"id":"$id","conversation_id":"c","kind":"message","state":"content",""" +
            """"hlc_ms":$hlc,"device_sequence":"00000000000000000001",$extra}"""

    private fun tombstone(id: String, hlc: Long, extra: String) =
        """{"id":"$id","conversation_id":"c","kind":"message","state":"tombstone","hlc_ms":$hlc,$extra}"""
}
