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
class TaskDirectoryDataTest {
    private class Oracle(val abi: FakeAbi = FakeAbi()) : NativeCore by abi {
        var version = 7uL
        var state = "proposed"
        var conversation = "c"
        var pending = false
        var wrongAck = false
        var holdMutation = false
        var pendingResolution = false
        var malformedDetail = false
        var receiptMode = false
        var loseAck = false
        var transitions = 0
        var approvalStatus = "pending"
        var approvalMode = "attended_local"
        fun approval() = """{"approval_id":"approval_srv_tool_1","server_id":"srv","tool_name":"tool","capability":"file.read","risk_level":"low","tool_scope":"read","confirmation_mode":"$approvalMode","expires_at":1900000000,"status":"$approvalStatus","details":{"arguments_hash":"h"}}"""
        val receipts = mutableMapOf<String, JsonObject>()
        fun row() = Json.parseToJsonElement("""{"task_id":"task","conversation_id":"$conversation","version":$version,"title":"Actual task","state":"$state","requester":"human","assignee":"agent","steps":[{"id":"step","title":"Actual step","state":"running","needs_me":null}],"result_summary":null,"pending_resolution":${if(pendingResolution) "{}" else "null"},"stalled_by_assignee":false}""").jsonObject
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val frame = Json.parseToJsonElement(request.decodeToString()).jsonObject
            val method = frame.getValue("method").jsonPrimitive.content
            abi.pendingRequests = holdMutation && method in setOf("task.accept", "task.decline", "task.cancel")
            abi.completionBody = when(method) {
                "task.list" -> buildJsonObject { put("tasks", buildJsonArray { add(row()) }); put("next_cursor", "opaque") }
                "task.get" -> if (malformedDetail) buildJsonObject { put("task_id", "task") } else row()
                "grant.list" -> Json.parseToJsonElement("""{"grants":[],"approvals":[${approval()}]}""").jsonObject
                "grant.approve", "grant.deny" -> { approvalStatus = if (method == "grant.approve") "approved" else "denied"; buildJsonObject { put("status", approvalStatus) } }
                "conversation.directory" -> Json.parseToJsonElement(
                    """{"conversations":[{"id":"c","kind":"direct","unread_count":0,"archived":false,"hidden":false,"is_unread":false},""" +
                        """{"id":"hidden","kind":"direct","unread_count":0,"archived":false,"hidden":true,"is_unread":false}],"next_cursor":"after-c"}"""
                ).jsonObject
                else -> if (receiptMode) {
                    val id = frame.getValue("body").jsonObject.getValue("message_id").jsonPrimitive.content
                    val receipt = receipts.getOrPut(id) {
                        transitions++; version++; state = if (method == "task.accept") "running" else "cancelled"
                        buildJsonObject { put("task_id", "task"); put("accepted", true); put("pending", false) }
                    }
                    if (loseAck) { loseAck = false; buildJsonObject { put("task_id", "wrong"); put("accepted", true); put("pending", false) } }
                    else receipt
                } else buildJsonObject { put("task_id", if(wrongAck) "wrong" else "task"); put("accepted", !pending); put("pending", pending) }
            }
            return abi.submit(core, request, ownership)
        }
    }
    @Test fun realBridgeScopesPagesAndExactUnsignedRevisionAndRejectsWrongConversation() = runTest(timeout = 5.seconds) {
        val native = Oracle().apply { version = ULong.MAX_VALUE }
        val client = (CoreClient.open(native, "/test/task-pages", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val first = (repo.taskPage("account", "c", null) as ClientResult.Ready).value
            assertEquals(ULong.MAX_VALUE, first.rows.single().version); assertNull(first.previous)
            assertEquals("", native.abi.submitted.last()["body"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
            val second = (repo.taskPage("account", "c", first.next) as ClientResult.Ready).value
            val request = native.abi.submitted.last(); assertEquals("account", request["account_id"]?.jsonPrimitive?.content)
            assertEquals("opaque", request["body"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
            assertNotNull("a forward-only listing still serves the cursor back to the first page", second.previous)
            assertTrue("an unknown cursor is refused", repo.taskPage("account", "c", "forged") is ClientResult.Failed)
            native.conversation = "wrong"
            assertTrue(repo.taskPage("account", "c", null) is ClientResult.Failed)
            assertTrue(repo.detail("account", "c", "task") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun acceptDeclineAndCancelBindFreshSnapshotAndNumericRevisionToOriginalMessageIdentity() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/task-decisions", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            for (decision in TaskDecision.entries) {
                val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
                val attempt = TaskAttempt("op-${decision.ordinal}", expected, decision)
                assertTrue(repo.mutate("account", attempt) is ClientResult.Ready)
                val frame = native.abi.submitted.last(); val body = frame.getValue("body").jsonObject
                assertEquals(attempt.operationId, frame["operation_id"]?.jsonPrimitive?.content)
                assertEquals(attempt.operationId, body["message_id"]?.jsonPrimitive?.content)
                assertEquals("c", body["conversation_id"]?.jsonPrimitive?.content)
                val field = if(decision == TaskDecision.CANCEL) "base_revision" else "version"
                assertFalse(body.getValue(field).jsonPrimitive.isString)
                assertEquals(if(decision == TaskDecision.CANCEL) "7" else "8", body.getValue(field).jsonPrimitive.content)
            }
            val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            native.version = 8u
            val before = native.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "task.accept" }
            assertTrue(repo.mutate("account", TaskAttempt("stale", expected, TaskDecision.ACCEPT)) is ClientResult.Failed)
            assertEquals(before, native.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "task.accept" })
            native.version = ULong.MAX_VALUE
            val maximum = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            assertFalse(maximum.decidable)
            assertTrue(repo.mutate("account", TaskAttempt("overflow", maximum, TaskDecision.ACCEPT)) is ClientResult.Failed)
            assertTrue(repo.mutate("account", TaskAttempt("max-cancel", maximum, TaskDecision.CANCEL)) is ClientResult.Ready)
            val numeric = native.abi.submitted.last().getValue("body").jsonObject.getValue("base_revision").jsonPrimitive
            assertFalse(numeric.isString); assertEquals(ULong.MAX_VALUE.toString(), numeric.content)
            native.pendingResolution = true
            assertFalse((repo.detail("account", "c", "task") as ClientResult.Ready).value.cancellable)
        } finally { client.close() }
    }
    @Test fun pendingAndUnknownKeepOriginalArgumentsAndAllowOnlyExactRetry() = runTest(timeout = 5.seconds) {
        val native = Oracle().apply { pending = true }
        val client = (CoreClient.open(native, "/test/task-journal", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            val attempt = TaskAttempt("original", expected, TaskDecision.ACCEPT)
            assertTrue((repo.mutate("account", attempt) as ClientResult.Ready).value.pending)
            assertEquals(attempt, repo.pending("account"))
            val original = native.abi.submitted.last(); val count = native.abi.submitted.size
            assertTrue(repo.mutate("account", attempt.copy(operationId = "new-id")) is ClientResult.Failed)
            assertEquals(count, native.abi.submitted.size)
            native.pending = false; native.wrongAck = true
            assertEquals(ClientOutcome.UNKNOWN, (repo.mutate("account", attempt) as ClientResult.Failed).problem.outcome)
            assertEquals(original.getValue("body"), native.abi.submitted.last().getValue("body"))
            native.version = 8u
            val mutations = native.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "task.accept" }
            assertEquals(ClientOutcome.UNKNOWN, (repo.mutate("account", attempt) as ClientResult.Failed).problem.outcome)
            assertEquals(mutations + 1, native.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "task.accept" })
            assertEquals(original.getValue("body"), native.abi.submitted.last().getValue("body"))
            assertEquals(attempt, repo.pending("account"))
        } finally { client.close() }
    }
    @Test fun newIntentReadFailureCannotAcquireReceiptReplayEligibility() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/task-preflight-read", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            native.malformedDetail = true
            val result = repo.mutate("account", TaskAttempt("unsubmitted", expected, TaskDecision.ACCEPT)) as ClientResult.Failed
            assertEquals(ClientOutcome.NONE, result.problem.outcome); assertNull(repo.pending("account"))
            assertFalse(native.abi.submitted.any { it["method"]?.jsonPrimitive?.content == "task.accept" })
            native.malformedDetail = false
            assertTrue(repo.mutate("account", TaskAttempt("after-read-recovery", expected, TaskDecision.ACCEPT)) is ClientResult.Ready)
        } finally { client.close() }
    }
    @Test fun appliedButAckLostReplaysOriginalReceiptAfterSnapshotAdvancesWithoutDuplicateTransition() = runTest(timeout = 5.seconds) {
        val native = Oracle().apply { receiptMode = true; loseAck = true }
        val client = (CoreClient.open(native, "/test/task-receipt-replay", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            val original = TaskAttempt("original-receipt", expected, TaskDecision.ACCEPT)
            assertEquals(ClientOutcome.UNKNOWN, (repo.mutate("account", original) as ClientResult.Failed).problem.outcome)
            val first = native.abi.submitted.last()
            val advanced = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            assertEquals(8uL, advanced.version); assertEquals("running", advanced.state); assertEquals(1, native.transitions)
            assertTrue(repo.mutate("account", original) is ClientResult.Ready)
            val replay = native.abi.submitted.last()
            assertEquals(first.getValue("operation_id"), replay.getValue("operation_id"))
            assertEquals(first.getValue("body"), replay.getValue("body"))
            assertEquals(1, native.transitions); assertNull(repo.pending("account"))
            val next = TaskAttempt("next-cancel", advanced, TaskDecision.CANCEL)
            assertTrue(repo.mutate("account", next) is ClientResult.Ready)
            assertEquals(2, native.transitions); assertEquals("cancelled", native.state)
            assertNull(repo.pending("account"))
        } finally { client.close() }
    }
    @Test fun cancellationRetainsIntentAndUnavailableContextCannotAuthorizeAnyOperation() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/task-cancel", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.detail("account", "c", "task") as ClientResult.Ready).value
            val attempt = TaskAttempt("cancelled-waiter", expected, TaskDecision.CANCEL)
            native.holdMutation = true
            val waiter = launch { repo.mutate("account", attempt) }; runCurrent(); waiter.cancelAndJoin()
            assertEquals(attempt, repo.pending("account")); assertTrue(native.abi.cancelled.isNotEmpty())
            val count = native.abi.submitted.size
            assertTrue(repo.contextualAvailability() is ClientResult.Unavailable)
            assertEquals(count, native.abi.submitted.size)
            native.holdMutation = false
            assertTrue(repo.permissionPage("account", null) is ClientResult.Ready)
            assertFalse(native.abi.submitted.any { it["method"]?.jsonPrimitive?.content in setOf("task.start", "task.resolve", "grant.approve", "grant.deny") })
        } finally { client.close() }
    }
    @Test fun contextsAreWindowedWithoutHiddenRowsAndPermissionsServeOneSnapshot() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/task-contexts", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val contexts = (repo.contextPage("account", null) as ClientResult.Ready).value
            assertEquals(listOf("c"), contexts.rows.map { it.id }); assertNull(contexts.previous); assertNotNull(contexts.next)
            val permissions = (repo.permissionPage("account", null) as ClientResult.Ready).value
            val approval = permissions.rows.single()
            assertEquals(TaskPermissionKind.APPROVAL, approval.kind); assertEquals("attended_local", approval.confirmation)
            assertTrue(approval.decidable && approval.approvable)
            assertNull(permissions.next); assertNull(permissions.previous)
        } finally { client.close() }
    }
    @Test fun approvalsAreReReadThenApprovedOrDeniedAndRemoteAppOnlyDenied() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/task-approvals", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        fun decisions() = native.abi.submitted.filter { it["method"]?.jsonPrimitive?.content in setOf("grant.approve", "grant.deny") }
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.permission("account", TaskPermissionKind.APPROVAL, "approval_srv_tool_1") as ClientResult.Ready).value
            native.approvalMode = "remote_app"
            assertTrue("a changed approval is refused before submission", repo.decideApproval("account", expected, true) is ClientResult.Failed)
            assertTrue(decisions().isEmpty())
            val remote = (repo.permission("account", TaskPermissionKind.APPROVAL, "approval_srv_tool_1") as ClientResult.Ready).value
            assertFalse(remote.approvable)
            assertTrue("a remote_app approval cannot be approved from the phone", repo.decideApproval("account", remote, true) is ClientResult.Failed)
            assertTrue(decisions().isEmpty())
            assertTrue(repo.decideApproval("account", remote, false) is ClientResult.Ready)
            val deny = decisions().single()
            assertEquals("grant.deny", deny["method"]?.jsonPrimitive?.content)
            assertEquals("approval_srv_tool_1", deny.getValue("body").jsonObject["approval_id"]?.jsonPrimitive?.content)
            assertEquals("account", deny["account_id"]?.jsonPrimitive?.content)
            assertTrue("a decided approval is no longer decidable", repo.decideApproval("account", remote, false) is ClientResult.Failed)
            native.approvalStatus = "pending"; native.approvalMode = "attended_local"
            assertTrue(repo.decideApproval("account", expected, true) is ClientResult.Ready)
            assertEquals("grant.approve", decisions().last()["method"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }
    @Test fun updatesSubscribeToTheGivenTopicsOnly() = runTest(timeout = 5.seconds) {
        val transport = object : RepositoryTransport {
            val filters = mutableListOf<SubscriptionFilter>()
            override suspend fun execute(request: CoreRequest): CoreResult<JsonObject> = CoreResult.Failure(CoreError.Closed())
            override suspend fun executeSecret(request: SecretRequest): CoreResult<SecretReply> = CoreResult.Failure(CoreError.Closed())
            override fun updates(filter: SubscriptionFilter): kotlinx.coroutines.flow.Flow<CoreUpdate> { filters += filter; return kotlinx.coroutines.flow.emptyFlow() }
        }
        val repo = DefaultTaskDirectoryRepository(transport)
        repo.updates("account", setOf(TaskTopic.TASKS, TaskTopic.APPROVAL_REQUESTED, TaskTopic.APPROVAL_DENIED, TaskTopic.APPROVAL_GRANTED)).collect {}
        repo.updates("account", setOf(TaskTopic.CONVERSATIONS)).collect {}
        repo.updates("account", emptySet()).collect {}
        val topics = transport.filters.map { filter -> filter.body.getValue("topics").jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(
            listOf(
                listOf("task.changed", "mcp.approval.request", "mcp.approval.granted", "mcp.approval.denied"),
                listOf("conversation.changed")
            ),
            topics
        )
    }
}
