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
    @Test fun realBridgeReadHasExactAccountConversationAndNoLowLevelSendFallback() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject {
            put("message_id", "m"); put("conversation_id", "c"); put("is_own", false); put("deleted", false); put("sender_id", "peer"); put("body_utf8", "approved")
        }) }) } }
        val client = (CoreClient.open(abi, "/test/history", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            assertEquals("approved", (repo.read("a", "c") as ClientResult.Ready).value.single().text)
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
