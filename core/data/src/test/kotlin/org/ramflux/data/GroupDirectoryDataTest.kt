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

private const val FRAME_BYTES = 1 shl 20
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
        /** When positive, `group.members` returns a generated roster of this many peers besides the own device. */
        var roster = 0
        /** Generated roster ids at real width (64-hex principal, root and device ids) instead of short labels. */
        var wideIds = false
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
        var listedConversation: String? = null
        fun detail(): JsonObject = Json.parseToJsonElement("""{"group_id":"g","name":"Actual name","group_epoch":"$epoch","members":["own-device","peer-device"],"roles":{"own-device":"$ownRole","peer-device":"member"},"member_roster":[{"identity":{"principal_id":"own","root_commitment":"own-root","device_id":"own-device"},"role":"$ownRole"},{"identity":{"principal_id":"peer","root_commitment":"peer-root","device_id":"peer-device"},"role":"member"}],"excluded_devices":[]}""").jsonObject
        private fun id(tag: Int, index: Int, label: String) = if (wideIds) "%02x".format(tag) + "%062x".format(index) else "$label-$index"
        fun large(count: Int): JsonObject {
            val devices = (0 until count).map { id(3, it, "peer-device") }
            return buildJsonObject {
                put("group_id", "g"); put("name", "Large"); put("group_epoch", "7")
                put("members", buildJsonArray { add("own-device"); devices.forEach { add(it) } })
                put("roles", buildJsonObject { put("own-device", "owner"); devices.forEach { put(it, "member") } })
                put("member_roster", buildJsonArray {
                    add(buildJsonObject {
                        put("identity", buildJsonObject { put("principal_id", "own"); put("root_commitment", "own-root"); put("device_id", "own-device") })
                        put("role", "owner")
                    })
                    devices.forEachIndexed { index, device ->
                        add(buildJsonObject {
                            put("identity", buildJsonObject { put("principal_id", id(1, index, "peer")); put("root_commitment", id(2, index, "peer-root")); put("device_id", device) })
                            put("role", "member")
                        })
                    }
                })
                put("excluded_devices", buildJsonArray {})
            }
        }
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val frame = Json.parseToJsonElement(request.decodeToString()).jsonObject
            val method = frame.getValue("method").jsonPrimitive.content
            val group = frame["body"]?.jsonObject?.get("group_id")?.jsonPrimitive?.content ?: "g"
            abi.completionBody = when(method) {
                "account.status" -> buildJsonObject {
                    put("local_account_id", "account"); put("principal_id", "own"); put("principal_commitment", "own-root")
                    put("device_id", "own-device"); put("local_device_authorized", authorized); put("authority_blocked", false)
                }
                "group.list" -> buildJsonObject {
                    put("groups", buildJsonArray { add(listedConversation?.let { JsonObject(detail() + ("conversation_id" to JsonPrimitive(it))) } ?: detail()) })
                }
                "group.members" -> if (roster > 0) large(roster) else detail()
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
            // A group created on this device: its list row names a conversation of its own, and that one is opened.
            oracle.listedConversation = "group-thread"
            assertEquals("group-thread", (repo.conversation("account", "g") as ClientResult.Ready).value)
            oracle.listedConversation = null
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
    @Test fun terminalReceiptsResolveOnlyMatchingOriginalIntentThroughRealBridge() = runTest(timeout = 5.seconds) {
        val oracle = Oracle().apply { malformed = true }
        val client = (CoreClient.open(oracle, "/test/group-receipts", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            val change = GroupChange(GroupOperation.RENAME, "g", 7u, name = "Original")
            val statuses = listOf("applied", "already_applied", "expired", "permission_denied", "rejected", "refused", "capacity", "invalid")
            for ((index, status) in statuses.withIndex()) {
                val id = "receipt-$index"
                assertEquals(ClientOutcome.UNKNOWN, (repo.mutate("account", change, id) as ClientResult.Failed).problem.outcome)
                oracle.requestStatus = status; oracle.requestId = "unrelated"
                repo.requests("account", "g")
                assertEquals(PendingGroupOperation(id, change), repo.pending("account"))
                oracle.requestId = id; oracle.requestStatus = "future_status"
                repo.requests("account", "g")
                assertEquals(PendingGroupOperation(id, change), repo.pending("account"))
                oracle.requestStatus = status
                repo.requests("account", "g")
                assertNull("terminal $status", repo.pending("account"))
                val count = oracle.abi.submitted.size
                val resolved = repo.mutate("account", change, id)
                assertEquals(count, oracle.abi.submitted.size)
                when (status) {
                    "applied", "already_applied" -> assertFalse((resolved as ClientResult.Ready).value.expired)
                    "expired" -> assertTrue((resolved as ClientResult.Ready).value.expired)
                    "capacity" -> assertEquals(ClientFailureReason.GROUP_CAPACITY, (resolved as ClientResult.Failed).problem.reason)
                    "invalid" -> assertEquals(ClientFailureReason.GROUP_INVALID, (resolved as ClientResult.Failed).problem.reason)
                    else -> assertEquals(ClientOutcome.REFUSED, (resolved as ClientResult.Failed).problem.outcome)
                }
            }
        } finally { client.close() }
    }
    @Test fun groupChatHeaderRequiresCanonicalBindingThroughRealBridge() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/group-header", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultMessageHistoryRepository(SdkRepositoryTransport { client })
            assertEquals(ConversationHeader("Actual name", "g"), (repo.groupHeader("account", "g", "canonical-conversation") as ClientResult.Ready).value)
            val count = oracle.abi.submitted.size
            assertTrue(repo.groupHeader("account", "g", "other-conversation") is ClientResult.Failed)
            // Neither the list row nor the group's metadata names a conversation here, so group.history decides last.
            assertEquals(listOf("group.list", "group.members", "group.history"), oracle.abi.submitted.drop(count).map { it["method"]?.jsonPrimitive?.content })
            assertTrue(repo.groupHeader("account", "wrong-group", "canonical-conversation") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun memberChangesAreRootQualifiedAndCancellationPreservesOriginalIntent() = runTest(timeout = 5.seconds) {
        val oracle = Oracle()
        val client = (CoreClient.open(oracle, "/test/group-roster", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client }, StandardTestDispatcher(testScheduler))
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

    @Test fun groupUpdatesUseSpecificJournalTopics() {
        assertFalse("*" in GROUP_TOPICS)
        assertEquals(listOf("group.changed", "group.invitation", "conversation.changed"), GROUP_TOPICS)
    }

    @Test fun unpagedGroupListsAreServedAsWindowPagesFromOneSnapshot() = runTest(timeout = 20.seconds) {
        val oracle = Oracle().apply { roster = 250 }
        val client = (CoreClient.open(oracle, "/test/group-pages", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            val started = System.nanoTime()
            val first = (repo.memberPage("account", "g") as ClientResult.Ready).value
            val decoded = (System.nanoTime() - started) / 1_000_000
            println("group roster 251 rows: first page (one group.members read and decode) in $decoded ms")
            assertEquals(100, first.rows.size)
            assertEquals(GroupRole.OWNER, first.rows.first().role)
            assertTrue(first.rows.all { it.active })
            assertNull(first.previous)
            var cursor = first.next
            var seen = first.rows.size
            while (cursor != null) {
                val page = (repo.memberPage("account", "g", cursor = cursor) as ClientResult.Ready).value
                seen += page.rows.size
                cursor = page.next
            }
            assertEquals(251, seen)
            assertEquals(1, oracle.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "group.members" })
            assertEquals(listOf("peer-7", "peer-70", "peer-71"), (repo.memberPage("account", "g", "PEER-7") as ClientResult.Ready).value.rows.map { it.principal }.take(3))
            assertTrue(repo.memberPage("account", "g", cursor = "nonsense") is ClientResult.Failed)
            assertEquals(listOf("g"), (repo.groupPage("account") as ClientResult.Ready).value.rows.map { it.id })
            assertTrue((repo.groupPage("account", "no such group") as ClientResult.Ready).value.rows.isEmpty())
            assertEquals(listOf("g"), (repo.invitationPage("account") as ClientResult.Ready).value.rows.map { it.id })
            assertEquals(listOf("r"), (repo.requestPage("account", "g") as ClientResult.Ready).value.rows.map { it.id })
            assertTrue(repo.groupPage("account", "x".repeat(257)) is ClientResult.Failed)
        } finally { client.close() }
    }

    /**
     * `group.members` is unpaged: the whole roster is one reply bounded by the 1 MiB frame (`ByteJson`).
     * With real-width ids every member costs a `members` entry, a `roles` entry and a roster row.
     */
    @Test fun aRosterFillingTheWholeFrameDecodesAndOneMemberTooManyIsATypedFailure() = runTest(timeout = 60.seconds) {
        val oracle = Oracle().apply { wideIds = true }
        val one = oracle.large(1).toString().length
        val perMember = oracle.large(2).toString().length - one
        val fits = (FRAME_BYTES - 256 - one) / perMember + 1
        oracle.roster = fits
        val client = (CoreClient.open(oracle, "/test/group-frame", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultGroupDirectoryRepository(SdkRepositoryTransport { client })
            val started = System.nanoTime()
            val first = (repo.memberPage("account", "g") as ClientResult.Ready).value
            println("group.members ${fits + 1} rows of $perMember bytes in one frame: first page in ${(System.nanoTime() - started) / 1_000_000} ms")
            assertTrue(fits > 2_000)
            var seen = first.rows.size
            var cursor = first.next
            while (cursor != null) {
                val page = (repo.memberPage("account", "g", cursor = cursor) as ClientResult.Ready).value
                seen += page.rows.size
                cursor = page.next
            }
            assertEquals(fits + 1, seen)
            oracle.roster = fits + 200
            assertTrue(repo.detail("account", "g") is ClientResult.Failed)
        } finally { client.close() }
    }
}
