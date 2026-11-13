// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
class MessageHistoryDataTest {
    @Test fun mixedNativeReplyAndDirectoryCarryCallTitleAndDeletedTime() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"messages":[{"message_id":"one","conversation_id":"c","created_at":10,"is_own":false,"deleted":false,"body_utf8":"approved"},{"message_id":"call","conversation_id":"c","kind":"call","created_at":11,"call":{"call_id":"call"},"duration_seconds":null,"missed":true},{"message_id":"deleted","conversation_id":"c","created_at":12,"is_own":true,"deleted":true,"sender_id":"must-hide","body_utf8":"must-hide"}]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/mixed", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            val rows = (repo.read("a", "c") as ClientResult.Ready).value
            assertEquals(listOf("one", "call", "deleted"), rows.map { it.id })
            assertTrue(rows[1] is CallSummary)
            assertEquals(12L, rows[2].createdAt)
            assertNull((rows[2] as MessageSummary).text)
            abi.completionBody = Json.parseToJsonElement("""{"conversations":[{"id":"c","title":"Actual title","unread_count":0,"is_unread":false,"archived":false,"hidden":false}],"next_cursor":null}""").jsonObject
            assertEquals("Actual title", (repo.header("a", "c") as ClientResult.Ready).value.title)
            assertEquals("conversation.directory", abi.submitted.last()["method"]?.jsonPrimitive?.content)
            assertEquals("a", abi.submitted.last()["account_id"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }

    @Test fun realBridgeReadHasExactAccountConversationAndNoLowLevelSendFallback() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject {
            put("message_id", "m"); put("conversation_id", "c"); put("is_own", false); put("deleted", false); put("sender_id", "peer"); put("body_utf8", "approved")
        }) }) } }
        val client = (CoreClient.open(abi, "/test/history", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            assertEquals("approved", (repo.read("a", "c") as ClientResult.Ready).value.single().let { (it as MessageSummary).text })
            assertTrue(repo.send("a", "c", "Draft") is ClientResult.Unavailable)
            assertTrue(repo.sendAvailability() is ClientResult.Unavailable)
            val request = abi.submitted.single()
            assertEquals("message.list", request["method"]?.jsonPrimitive?.content)
            assertEquals("a", request["account_id"]?.jsonPrimitive?.content)
            assertEquals("c", request["body"]?.jsonObject?.get("conversation_id")?.jsonPrimitive?.content)
            assertTrue(repo.read("a", "other") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun malformedReadDoesNotReturnInventedEmptyHistory() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/history-invalid", "a".repeat(64)) as CoreResult.Success).value
        try { assertTrue(DefaultMessageHistoryRepository(SdkRepositoryTransport { client }).read("a", "c") is ClientResult.Failed) }
        finally { client.close() }
    }
}
