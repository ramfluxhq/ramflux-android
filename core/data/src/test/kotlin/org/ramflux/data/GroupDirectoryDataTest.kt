// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDirectoryDataTest {
    private class Oracle : NativeCore {
        val abi = FakeAbi()
        var authorized = true
        var epoch = 7uL
        var ownRole = "owner"
        var pending = false
        var malformed = false
        var holdMutation = false
        var requestStatus = "waiting_for_owner"
        var requestId = "r"
        private val delegate = abi
        override fun negotiate() = delegate.negotiate()
        override fun open(config: ByteArray) = delegate.open(config)
        override fun close(core: Long) = delegate.close(core)
        override fun releaseClosed(core: Long) = delegate.releaseClosed(core)
        override fun operationPoll(core: Long, operation: Long) = delegate.operationPoll(core, operation)
        override fun operationCancel(core: Long, operation: Long) = delegate.operationCancel(core, operation)
        override fun operationRelease(core: Long, operation: Long) = delegate.operationRelease(core, operation)
        override fun eventReset(core: Long, generation: Long) = delegate.eventReset(core, generation)
        override fun eventPoll(core: Long) = delegate.eventPoll(core)
        fun detail(): JsonObject = Json.parseToJsonElement("""{"group_id":"g","name":"Actual name","group_epoch":"$epoch","members":["own-device","peer-device"],"roles":{"own-device":"$ownRole","peer-device":"member"},"member_roster":[{"identity":{"principal_id":"own","root_commitment":"own-root","device_id":"own-device"},"role":"$ownRole"},{"identity":{"principal_id":"peer","root_commitment":"peer-root","device_id":"peer-device"},"role":"member"}],"excluded_devices":[]}""").jsonObject
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val frame = Json.parseToJsonElement(request.decodeToString()).jsonObject
            val method = frame.getValue("method").jsonPrimitive.content
            val group = frame["body"]?.jsonObject?.get("group_id")?.jsonPrimitive?.content ?: "g"
            abi.completionBody = when(method) {
                "account.status" -> buildJsonObject {
                    put("local_account_id", "account"); put("principal_id", "own"); put("principal_commitment", "own-root")
                    put("device_id", "own-device"); put("local_device_authorized", authorized); put("authority_blocked", false)
                }
                "group.list" -> buildJsonObject { put("groups", buildJsonArray { add(detail()) }) }
                "group.members" -> detail()
                "group.invitations.list" -> Json.parseToJsonElement("""{"invitations":[{"group_id":"g","name":"Invite","creator":"creator","inviter":"inviter","expires_at":2000000000}]}""").jsonObject
                "group.requests.list" -> Json.parseToJsonElement("""{"requests":[{"group_id":"g","request_id":"$requestId","status":"$requestStatus","waiting_for_owner":${requestStatus == "waiting_for_owner"},"expires_at":2000000000}]}""").jsonObject
                "contact.list" -> Json.parseToJsonElement("""{"contacts":[{"link_id":"link","principal_id":"peer","display_name":"Name","state":"accepted","blocked":false,"verified":false,"root_commitment":"peer-root"}]}""").jsonObject
                "group.history" -> buildJsonObject { put("group_id", group); put("conversation_id", "canonical-conversation") }
                else -> buildJsonObject {
                    put("group_id", if(malformed) "wrong" else group)
                    put("accepted", method != "group.invitation.decline")
                    if(method.startsWith("group.invitation.")) put("declined", method == "group.invitation.decline")
                    put("pending", pending)
                    if(pending) put("request_id", "r")
                }
            }
            abi.pendingRequests = holdMutation && method == "group.name.set"
            return delegate.submit(core, request, ownership)
        }
    }
    @Test fun exactMetadataCanonicalConversationAndFreshPermissionGateUseRealBridge() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/group-read", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            assertTrue((repo.detail("account", "g") as ClientResult.Ready).value.canManage)
            oracle.epoch = ULong.MAX_VALUE
            assertEquals(ULong.MAX_VALUE, (repo.list("account") as ClientResult.Ready).value.single().epoch)
            assertEquals("canonical-conversation", (repo.conversation("account", "g") as ClientResult.Ready).value)
            oracle.epoch = 7u; oracle.ownRole = "member"
            assertTrue(repo.mutate("account", GroupChange(GroupOperation.RENAME, "g", 7u, name = "New"), "rename-op") is ClientResult.Failed)
            assertFalse(oracle.abi.submitted.any { it["method"]?.jsonPrimitive?.content == "group.name.set" })
        } finally { client.close() }
    }
    @Test fun supportedMutationsCarryStableScopeAndReturnActualPendingOrUnknown() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/group-mutations", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            val created = repo.mutate("account", GroupChange(GroupOperation.CREATE, "new-group", name = "New"), "create-op")
            assertTrue(created is ClientResult.Ready)
            val create = oracle.abi.submitted.last()
            assertEquals("account", create["account_id"]?.jsonPrimitive?.content)
            assertEquals("create-op", create["operation_id"]?.jsonPrimitive?.content)
            assertEquals("own-device", create["body"]?.jsonObject?.get("creator_id")?.jsonPrimitive?.content)
            val change = GroupChange(GroupOperation.RENAME, "g", 7u, name = "New name")
            oracle.pending = true
            assertTrue((repo.mutate("account", change, "rename-op") as ClientResult.Ready).value.waiting)
            oracle.pending = false; oracle.malformed = true
            val result = repo.mutate("account", change, "unknown-op") as ClientResult.Failed
            assertEquals(ClientOutcome.UNKNOWN, result.problem.outcome)
            assertEquals("unknown-op", repo.pending("account")?.operationId)
            assertEquals(change, repo.pending("account")?.change)
            val count = oracle.abi.submitted.size
            repo.mutate("account", change, "other-op")
            assertEquals(count, oracle.abi.submitted.size)
            oracle.requestStatus = "applied"
            oracle.requestId = "unknown-op"
            assertTrue(repo.requests("account", "g") is ClientResult.Ready)
            assertNull(repo.pending("account"))
        } finally { client.close() }
    }
    @Test fun memberChangesAreRootQualifiedAndCancellationPreservesOriginalIntent() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/group-roster", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            val member = (repo.detail("account", "g") as ClientResult.Ready).value.roster.last()
            assertTrue(repo.mutate("account", GroupChange(GroupOperation.ROLE, "g", 7u, member = member, role = GroupRole.ADMIN), "role-op") is ClientResult.Ready)
            assertEquals("peer-root", oracle.abi.submitted.last()["body"]?.jsonObject?.get("member_principal_commitment")?.jsonPrimitive?.content)
            oracle.holdMutation = true
            val change = GroupChange(GroupOperation.RENAME, "g", 7u, name = "Later")
            val pending = launch { repo.mutate("account", change, "cancel-op") }
            runCurrent()
            pending.cancelAndJoin()
            assertEquals("cancel-op", repo.pending("account")?.operationId)
            assertEquals(change, repo.pending("account")?.change)
            assertTrue(oracle.abi.cancelled.isNotEmpty())
        } finally { client.close() }
    }
}
