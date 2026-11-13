// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class OnboardingDataTest {
    private class PublicTransport : RepositoryTransport {
        val calls = mutableListOf<CoreRequest>()
        var answer: (CoreRequest) -> CoreResult<JsonObject> = { CoreResult.Failure(CoreError.Closed()) }
        override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> { calls.add(request); return answer(request) }
        override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> { request.body.close(); return CoreResult.Failure(CoreError.Closed()) }
        override fun updates(filter: SubscriptionFilter) = emptyFlow<CoreUpdate>()
    }
    private fun node(changed: Boolean = false) = buildJsonObject {
        put("node_id", "node"); put("discovery_url", "https://node.example"); put("node_public_key", "reviewed-key"); put("enabled", true)
        put("safety_warnings", buildJsonArray { if (changed) add("key_changed") })
    }
    private fun reply(body: JsonObject) = CoreResult.Success(buildJsonObject { put("body", body) })
    @Test fun confirmationPinsReviewedKeyAndMutationIdentity() = runTest(timeout = 5.seconds) {
        val transport = PublicTransport().apply { answer = { reply(node()) } }
        val repo = DefaultNodeSetupRepository(transport)
        val candidate = DiscoveredNode(SavedNode("node", "https://node.example", "reviewed-key", false, false, true), "a".repeat(64))
        assertTrue(repo.confirm(candidate, "reviewed-key") is ClientResult.Ready)
        assertTrue(repo.confirm(candidate, "different-operator-key") is ClientResult.Failed)
        val request = transport.calls.single()
        assertEquals("operator", request.body["pin_source"]?.jsonPrimitive?.content)
        assertEquals("node.confirm", request.method); assertEquals("reviewed-key", request.body["expected_public_key"]?.jsonPrimitive?.content)
        assertFalse(request.operationId.isNullOrBlank())
        assertTrue(repo.confirm(candidate.copy(node = candidate.node.copy(keyChanged = true)), "reviewed-key") is ClientResult.Unavailable)
        assertEquals(1, transport.calls.size)
    }
    @Test fun selectingNodeRechecksSafetyAndNeverWritesChangedKey() = runTest(timeout = 5.seconds) {
        val transport = PublicTransport().apply { answer = { reply(buildJsonObject { put("nodes", buildJsonArray { add(node(true)) }) }) } }
        assertTrue(DefaultNodeSetupRepository(transport).select("node") is ClientResult.Failed)
        assertEquals(listOf("node.list"), transport.calls.map { it.method })
    }
    @Test fun malformedReadIsTypedFailureRatherThanEmptyCollection() = runTest(timeout = 5.seconds) {
        val transport = PublicTransport().apply { answer = { reply(buildJsonObject { put("nodes", "invalid") }) } }
        assertTrue(DefaultNodeSetupRepository(transport).list() is ClientResult.Failed)
        assertTrue(DefaultNodeSetupRepository(transport).discover("https://name:password@node.example") is ClientResult.Failed)
        assertEquals(1, transport.calls.size)
    }
    @Test fun unknownMutationOutcomeIsPreservedWithoutAutomaticRetry() = runTest(timeout = 5.seconds) {
        val transport = PublicTransport().apply { answer = { CoreResult.Failure(CoreError.Native(10, Effect.UNKNOWN)) } }
        val candidate = DiscoveredNode(SavedNode("node", "https://node.example", "key", false, false, true), "a".repeat(64))
        val failure = DefaultNodeSetupRepository(transport).confirm(candidate, "key") as ClientResult.Failed
        assertEquals(ClientOutcome.UNKNOWN, failure.problem.outcome); assertEquals(1, transport.calls.size)
    }
    @Test fun accountListMapsRealBridgeReplyAndCustodyOperationsNeverCallNative() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject {
            put("account_details", buildJsonArray { add(buildJsonObject { put("id", "a"); put("label", "Work"); put("state", "locked") }) }); put("active_account_id", JsonNull)
        } }
        val client = (CoreClient.open(abi, "/test/onboarding", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountAccessRepository(SdkRepositoryTransport { client })
            val rows = (repo.list() as ClientResult.Ready).value
            assertEquals("Work", rows.accounts.single().label); assertFalse(rows.accounts.single().unlocked)
            assertTrue(repo.select("a") is ClientResult.Unavailable)
            for (action in AccountSetupAction.entries) assertTrue(repo.setup(action) is ClientResult.Unavailable)
            assertEquals(listOf("account.list", "account.list"), abi.submitted.map { it.getValue("method").jsonPrimitive.content })
            assertTrue(abi.returnedBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
        } finally { client.close() }
    }

    @Test fun missingDiscoveryNodeOrFingerprintIsTypedFailure() = runTest(timeout = 5.seconds) {
        for (body in listOf(buildJsonObject { put("key_fingerprint", "a".repeat(64)) },
            buildJsonObject { put("node", node()) }, buildJsonObject { put("node", JsonNull); put("key_fingerprint", 3) })) {
            val transport = PublicTransport().apply { answer = { reply(body) } }
            assertTrue(DefaultNodeSetupRepository(transport).discover("https://node.example") is ClientResult.Failed)
            assertEquals(1, transport.calls.size)
        }
    }
    @Test fun originReviewStripsUnapprovedPathQueryAndFragmentButRejectsCredentials() {
        assertEquals(DiscoveryOrigin("Node.Example/team?source=invite#join", "https://node.example"),
            DiscoveryOriginParser.preview("Node.Example/team?source=invite#join"))
        assertNull(DiscoveryOriginParser.preview("https://user:secret@node.example/path"))
        assertNull(DiscoveryOriginParser.preview("http://node.example"))
    }
}
