// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
class ConversationDirectoryDataTest {
    @Test fun directoryMapsSdkVisibilityCountsAndScopedCursorViaRealBridge() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject {
            put("conversations", buildJsonArray { for ((id, hidden) in listOf("visible" to false, "hidden" to true)) add(buildJsonObject {
                put("id", id); put("title", "Team"); put("unread_count", 7); put("archived", true); put("hidden", hidden)
            }) }); put("next_cursor", "opaque-next")
        } }
        val client = (CoreClient.open(abi, "/test/directory", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultConversationDirectoryRepository(SdkRepositoryTransport { client })
            val page = (repo.page("account", "opaque-current") as ClientResult.Ready).value
            assertEquals(listOf(ConversationSummary("visible", "Team", 7u, true)), page.rows)
            assertEquals("opaque-next", page.nextCursor)
            val request = abi.submitted.single()
            assertEquals("conversation.directory", request["method"]?.jsonPrimitive?.content)
            assertEquals("account", request["account_id"]?.jsonPrimitive?.content)
            assertEquals("opaque-current", request["body"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
            assertEquals(100, request["body"]?.jsonObject?.get("limit")?.jsonPrimitive?.int)
            assertTrue(repo.searchAvailability() is ClientResult.Unavailable)
            assertTrue(repo.openAvailability("account", "visible") is ClientResult.Unavailable)
        } finally { client.close() }
    }
    @Test fun malformedDirectoryAndClosedHostRemainTypedFailures() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/directory-invalid", "a".repeat(64)) as CoreResult.Success).value
        val repo = DefaultConversationDirectoryRepository(SdkRepositoryTransport { client })
        assertTrue(repo.page("account", null) is ClientResult.Failed)
        client.close(); assertTrue(repo.page("account", null) is ClientResult.Failed)
    }
}
