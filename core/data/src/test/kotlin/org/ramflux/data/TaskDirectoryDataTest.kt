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
        fun row() = Json.parseToJsonElement("""{"task_id":"task","conversation_id":"$conversation","version":$version,"title":"Actual task","state":"$state","requester":"human","assignee":"agent","steps":[{"id":"step","title":"Actual step","state":"running","needs_me":null}],"result_summary":null,"pending_resolution":${if(pendingResolution) "{}" else "null"},"stalled_by_assignee":false}""").jsonObject
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val frame = Json.parseToJsonElement(request.decodeToString()).jsonObject
            val method = frame.getValue("method").jsonPrimitive.content
            abi.pendingRequests = holdMutation && method in setOf("task.accept", "task.decline", "task.cancel")
            abi.completionBody = when(method) {
                "task.list" -> buildJsonObject { put("tasks", buildJsonArray { add(row()) }); put("next_cursor", "opaque") }
                "task.get" -> row()
                "grant.list" -> Json.parseToJsonElement("""{"grants":[],"approvals":[]}""").jsonObject
                else -> buildJsonObject { put("task_id", if(wrongAck) "wrong" else "task"); put("accepted", !pending); put("pending", pending) }
            }
            return abi.submit(core, request, ownership)
        }
    }
    @Test fun realBridgeScopesPagesAndExactUnsignedRevisionAndRejectsWrongConversation() = runTest(timeout = 5.seconds) {
        val native = Oracle().apply { version = ULong.MAX_VALUE }
        val client = (CoreClient.open(native, "/test/task-pages", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultTaskDirectoryRepository(SdkRepositoryTransport { client })
            val page = (repo.page("account", "c", "original-cursor") as ClientResult.Ready).value
            assertEquals(ULong.MAX_VALUE, page.rows.single().version); assertEquals("opaque", page.cursor)
            val request = native.abi.submitted.last(); assertEquals("account", request["account_id"]?.jsonPrimitive?.content)
            assertEquals("original-cursor", request["body"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content)
            native.conversation = "wrong"
            assertTrue(repo.page("account", "c", null) is ClientResult.Failed)
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
            assertEquals(mutations, native.abi.submitted.count { it["method"]?.jsonPrimitive?.content == "task.accept" })
            assertEquals(attempt, repo.pending("account"))
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
            assertTrue(repo.permissions("account") is ClientResult.Ready)
            assertFalse(native.abi.submitted.any { it["method"]?.jsonPrimitive?.content in setOf("task.start", "task.resolve", "grant.approve", "grant.deny") })
        } finally { client.close() }
    }
}
