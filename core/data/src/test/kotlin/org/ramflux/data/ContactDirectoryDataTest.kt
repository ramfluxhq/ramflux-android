// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
class ContactDirectoryDataTest {
    @Test fun actualBridgeFiltersUnacceptedLinksAndScopesAccountWithoutUnsupportedMutations() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contacts":[{"link_id":"yes","principal_id":"peer","state":"accepted","blocked":false,"verified":true,"display_name":"Actual name"},{"link_id":"pending","principal_id":"other","state":"pending","blocked":false,"verified":false},{"link_id":"blocked","principal_id":"third","state":"accepted","blocked":true,"verified":false}]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/contacts", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            assertEquals(listOf(ContactSummary("yes", "peer", "Actual name", true)), (repo.contacts("account") as ClientResult.Ready).value)
            for (operation in ContactOperation.entries) assertTrue(repo.availability(operation) is ClientResult.Unavailable)
            assertEquals("contact.list", abi.submitted.single()["method"]?.jsonPrimitive?.content)
            assertEquals("account", abi.submitted.single()["account_id"]?.jsonPrimitive?.content)
            abi.completionBody = buildJsonObject {}
            assertTrue(repo.contacts("account") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun requestAdapterPreservesRecordedStatusAndNoPreviewBecomesMessageText() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"requests":[{"request_id":"request","principal_id":"peer","direction":"outbound","state":"expired","note":null,"message_count":0,"expires_at":42,"preview":{"encrypted_body":"must-not-copy"}}]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/requests", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val row = (repo.requests("account") as ClientResult.Ready).value.single()
            assertEquals(ContactRequestStatus.EXPIRED, row.status)
            assertEquals(RequestDirection.OUTBOUND, row.direction)
            assertNull(row.note)
            assertEquals("contact.requests.list", abi.submitted.single()["method"]?.jsonPrimitive?.content)
            assertEquals("account", abi.submitted.single()["account_id"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }
}
