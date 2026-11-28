// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.core.data.BuildConfig
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
    @Test fun lockedAccountUnlocksThroughTheAccountAbiAndSetupStaysLocal() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject {
            put("account_details", buildJsonArray { add(buildJsonObject { put("id", "a"); put("display_name", "Work"); put("state", "locked") }) }); put("active_account_id", JsonNull)
        } }
        val client = (CoreClient.open(abi, "/test/onboarding", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountAccessRepository(SdkRepositoryTransport { client })
            val revisions = mutableListOf<Int>()
            val watch = launch { repo.revisions.collect { revisions.add(it) } }
            yield()
            val rows = (repo.list() as ClientResult.Ready).value
            assertEquals("Work", rows.accounts.single().label); assertFalse(rows.accounts.single().unlocked)
            assertTrue(repo.select("a") is ClientResult.Unavailable)
            for (action in AccountSetupAction.entries) assertTrue(repo.setup(action) is ClientResult.Unavailable)
            assertEquals(listOf("account.list", "account.list"), abi.submitted.map { it.getValue("method").jsonPrimitive.content })
            val passphrase = "482915".toCharArray()
            assertTrue(repo.unlock("a", passphrase) is ClientResult.Ready)
            yield()
            assertEquals(listOf(1), revisions)
            assertTrue(passphrase.all { it == '\u0000' })
            // After the switch one list read names the account for this device's registry of known accounts.
            assertEquals(listOf("account.unlock", "account.switch"), abi.submitted.drop(2).map { it.getValue("method").jsonPrimitive.content })
            val unlock = abi.submitted[2]
            assertEquals("a", unlock.getValue("account_id").jsonPrimitive.content)
            assertEquals("482915", unlock.getValue("body").jsonObject.getValue("passphrase").jsonPrimitive.content)
            assertEquals(0, abi.submitted[3].getValue("body").jsonObject.size)
            val blank = "   ".toCharArray()
            assertTrue(repo.unlock("a", blank) is ClientResult.Failed)
            assertTrue(blank.all { it == '\u0000' })
            val short = "4829".toCharArray()
            val refused = repo.unlock("a", short) as ClientResult.Failed
            assertEquals("an incomplete code is refused before the SDK", AccountCodeRefusal.NOT_SIX_DIGITS, refused.problem.accountCodeRefusal())
            assertTrue(short.all { it == '\u0000' })
            abi.completionCode = 8
            val rejected = "739164".toCharArray()
            assertTrue(repo.unlock("a", rejected) is ClientResult.Failed)
            assertTrue(rejected.all { it == '\u0000' })
            assertEquals("account.unlock", abi.submitted.last().getValue("method").jsonPrimitive.content)
            assertEquals(listOf(1), revisions)
            watch.cancel()
            assertTrue(abi.returnedBuffers.all { buffer -> buffer.all { it == 0.toByte() } })
        } finally { client.close() }
    }

    @Test fun aSwitchAnAccountLockASignOutAndARemovalClearTheMlsStateOfTheAccountsTheyLeave() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject {
            put("account_details", buildJsonArray {
                add(buildJsonObject { put("id", "a"); put("display_name", "Work"); put("state", "unlocked") })
                add(buildJsonObject { put("id", "b"); put("display_name", "Home"); put("state", "unlocked") })
            }); put("active_account_id", "a")
        } }
        val client = (CoreClient.open(abi, "/test/onboarding", "a".repeat(64)) as CoreResult.Success).value
        try {
            val stores = AccountScopedStores()
            val mls = MlsGroupSession(UnavailableMlsGroups, backgroundScope).also { it.attach(stores) }
            val repo = DefaultAccountAccessRepository(SdkRepositoryTransport { client }, stores)
            mls.groups("a")
            mls.groups("b")
            // A switch to b clears a, the account left behind; b was not purged, so its epoch stays.
            assertTrue(repo.select("b") is ClientResult.Ready)
            assertEquals(1L, mls.epoch("a").first())
            assertEquals(0L, mls.epoch("b").first())
            // Back to a: b is cleared, and a is admitted (its epoch moves once more).
            assertTrue(repo.select("a") is ClientResult.Ready)
            assertEquals(2L, mls.epoch("a").first())
            assertEquals(1L, mls.epoch("b").first())
            // The account lock, sign-out and removal each clear the account they act on.
            assertTrue(repo.lock("a") is ClientResult.Ready)
            assertEquals(3L, mls.epoch("a").first())
            assertTrue(repo.signOut("b") is ClientResult.Ready)
            assertEquals(2L, mls.epoch("b").first())
            assertTrue(repo.removeLocal("a") is ClientResult.Ready)
            assertEquals(4L, mls.epoch("a").first())
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

    /** The rule of each build type: debug admits plain HTTP to local hosts only, release never does. */
    @Test fun plainHttpReachesOnlyLocalHostsAndOnlyInDebugBuilds() {
        val local = listOf(
            "http://127.0.0.1:8482" to "http://127.0.0.1:8482",
            "http://10.0.2.2:8482" to "http://10.0.2.2:8482",
            "http://192.168.1.20:8482/" to "http://192.168.1.20:8482",
            "http://172.16.0.5:80" to "http://172.16.0.5",
            "HTTP://LOCALHOST:8482" to "http://localhost:8482",
            "http://[::1]:8482" to "http://[::1]:8482"
        )
        for ((input, origin) in local) {
            assertEquals(input, origin, DiscoveryOriginParser.preview(input, localHttp = true)?.origin)
            assertNull("release must refuse $input", DiscoveryOriginParser.preview(input, localHttp = false))
        }
        for (remote in listOf("http://node.example", "http://8.8.8.8", "http://172.32.0.1", "http://192.169.0.1",
            "http://010.0.2.2", "http://10.0.2", "http://user:pw@10.0.2.2", "ftp://10.0.2.2")) {
            assertNull("debug must refuse $remote", DiscoveryOriginParser.preview(remote, localHttp = true))
        }
        // An address without a scheme stays HTTPS in both build types.
        assertEquals("https://10.0.2.2:8482", DiscoveryOriginParser.preview("10.0.2.2:8482", localHttp = true)?.origin)
    }

    @Test fun theLocalNodeAllowanceFollowsTheBuildType() {
        assertEquals("debug builds only", BuildConfig.BUILD_TYPE == "debug", BuildConfig.LOCAL_NODE_HTTP)
    }

    @Test fun discoveryAcceptsTheOriginThePreviewApproved() = runTest(timeout = 5.seconds) {
        val transport = PublicTransport().apply { answer = { reply(buildJsonObject {}) } }
        DefaultNodeSetupRepository(transport).discover("http://10.0.2.2:8482")
        assertEquals(if (BuildConfig.LOCAL_NODE_HTTP) 1 else 0, transport.calls.size)
    }
}
