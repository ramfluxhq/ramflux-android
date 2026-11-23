// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.sdk.CoreClient
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.FakeAbi

class OwnContactCardDataTest {
    @Test fun ownCardIsExportedThroughTheContactAbiWithItsLink() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply {
            completionBody = buildJsonObject { put("card", "ramflux-card:v3:abc"); put("link", "ramflux://contact/v3/abc") }
        }
        val client = (CoreClient.open(abi, "/test/own-card", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val card = (repo.ownCard("account") as ClientResult.Ready).value
            assertEquals("ramflux-card:v3:abc", card.text)
            assertEquals("ramflux://contact/v3/abc", card.link)
            assertEquals("OwnContactCard(<redacted>)", card.toString())
            val frame = abi.submitted.single()
            assertEquals("contact.card.export", frame.getValue("method").jsonPrimitive.content)
            assertEquals("account", frame.getValue("account_id").jsonPrimitive.content)
            assertFalse(frame.getValue("operation_id").jsonPrimitive.content.isBlank())
            abi.completionBody = buildJsonObject { put("card", "not a card"); put("link", "https://example.invalid") }
            assertTrue("a reply that is not a card is refused", repo.ownCard("account") is ClientResult.Failed)
            abi.completionCode = 8
            assertTrue(repo.ownCard("account") is ClientResult.Failed)
        } finally {
            client.close()
        }
    }
}
