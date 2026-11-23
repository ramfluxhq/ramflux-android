// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

private const val FRAME_BYTES = 1 shl 20
class ContactDirectoryDataTest {
    @Test fun actualBridgeFiltersUnacceptedLinksAndScopesAccountWithoutUnsupportedMutations() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contacts":[{"link_id":"yes","principal_id":"peer","state":"accepted","blocked":false,"verified":true,"display_name":"Actual name"},{"link_id":"pending","principal_id":"other","state":"pending","blocked":false,"verified":false},{"link_id":"blocked","principal_id":"third","state":"accepted","blocked":true,"verified":false}]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/contacts", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            assertEquals(listOf(ContactSummary("yes", "peer", "Actual name", true)), (repo.contacts("account") as ClientResult.Ready).value)
            assertTrue(repo.availability(ContactOperation.ACCEPT) is ClientResult.Ready)
            assertTrue(repo.availability(ContactOperation.REPORT) is ClientResult.Unavailable)
            assertTrue(repo.availability(ContactOperation.VERIFY) is ClientResult.Ready)
            assertTrue(repo.availability(ContactOperation.REMOVE) is ClientResult.Ready)
            assertEquals("contact.list", abi.submitted.single()["method"]?.jsonPrimitive?.content)
            assertEquals("account", abi.submitted.single()["account_id"]?.jsonPrimitive?.content)
            abi.completionBody = buildJsonObject {}
            assertTrue(repo.contacts("account") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun unpagedListsAreServedAsWindowPagesWithScopeQueryAndCursors() = runTest(timeout = 5.seconds) {
        val rows = (0 until 250).joinToString(",") { """{"link_id":"l$it","principal_id":"p$it","state":"accepted","blocked":${it % 50 == 0},"verified":false,"display_name":"Name $it"}""" }
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contacts":[$rows]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/contact-pages", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val first = (repo.contactPage("account", ContactListScope.ACCEPTED) as ClientResult.Ready).value
            assertEquals(100, first.rows.size)
            assertNull(first.previous)
            val second = (repo.contactPage("account", ContactListScope.ACCEPTED, cursor = first.next) as ClientResult.Ready).value
            val third = (repo.contactPage("account", ContactListScope.ACCEPTED, cursor = second.next) as ClientResult.Ready).value
            assertEquals(245, (first.rows + second.rows + third.rows).map { it.id }.distinct().size)
            assertNull(third.next)
            assertEquals(first.rows, (repo.contactPage("account", ContactListScope.ACCEPTED, cursor = second.previous) as ClientResult.Ready).value.rows)
            // One SDK read per snapshot; cursors page inside it.
            assertEquals(1, abi.submitted.size)
            assertEquals(listOf("l0", "l50", "l100", "l150", "l200"), (repo.contactPage("account", ContactListScope.BLOCKED) as ClientResult.Ready).value.rows.map { it.id })
            assertEquals(listOf("l7", "l70", "l71"), (repo.contactPage("account", ContactListScope.ACCEPTED, "name 7") as ClientResult.Ready).value.rows.map { it.id }.take(3))
            assertTrue(repo.contactPage("account", ContactListScope.ACCEPTED, cursor = "nonsense") is ClientResult.Failed)
        } finally { client.close() }
    }
    /**
     * `contact.list` is unpaged: the whole roster is one reply bounded by the 1 MiB frame (`ByteJson`).
     * Rows use realistic sizes (64-hex link, principal and root ids, a 24-character name).
     */
    @Test fun aContactRosterFillingTheWholeFrameDecodesAndOneRowMoreIsATypedFailure() = runTest(timeout = 60.seconds) {
        fun hex(tag: Int, value: Int) = "%02x".format(tag) + "%062x".format(value)
        fun row(it: Int) = """{"link_id":"${hex(1, it)}","principal_id":"${hex(2, it)}","state":"accepted","blocked":false,"verified":${it % 3 == 0},"display_name":"Contact name number ${"%05d".format(it)}","root_commitment":"${hex(3, it)}"}"""
        val rowBytes = row(1).length + 1 // the longest row shape ("verified":false)
        // Envelope headroom: `{"code":0,"response":{"body":...}}` plus the list key.
        val fits = (FRAME_BYTES - 256) / rowBytes
        val abi = FakeAbi().apply { completionBody = Json.parseToJsonElement("""{"contacts":[${(0 until fits).joinToString(",") { row(it) }}]}""").jsonObject }
        val client = (CoreClient.open(abi, "/test/contact-frame", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultContactDirectoryRepository(SdkRepositoryTransport { client })
            val started = System.nanoTime()
            val firstResult = repo.contactPage("account", ContactListScope.ACCEPTED)
            assertTrue("$firstResult", firstResult is ClientResult.Ready)
            val first = (firstResult as ClientResult.Ready).value
            println("contact.list $fits rows of $rowBytes bytes in one frame: first page in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue(fits > 3_000)
            var seen = first.rows.size
            var cursor = first.next
            while (cursor != null) {
                val page = (repo.contactPage("account", ContactListScope.ACCEPTED, cursor = cursor) as ClientResult.Ready).value
                seen += page.rows.size
                cursor = page.next
            }
            assertEquals(fits, seen)
            abi.completionBody = Json.parseToJsonElement("""{"contacts":[${(0 until fits + 300).joinToString(",") { row(it) }}]}""").jsonObject
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
