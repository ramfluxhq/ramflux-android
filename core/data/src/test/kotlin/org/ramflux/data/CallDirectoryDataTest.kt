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
class CallDirectoryDataTest {
    private class Oracle(val abi: FakeAbi = FakeAbi()) : NativeCore by abi {
        var sequence = 7uL
        var generation = 3uL
        var state = "incoming_ringing"
        var reason = "missed"
        var wrongReply = false
        var hold = false
        fun row(peer: String = "peer") = Json.parseToJsonElement("""{"call_id":"call","conversation_id":"conversation","peer_id":"$peer","direction":"incoming","state":"$state","seq":"$sequence","media_generation":"$generation","created_at":1700000000,"answered_at":null,"ended_at":null,"end_reason":"${if(state == "ended") reason else ""}"}""").jsonObject
        override fun submit(core: Long, request: ByteArray, ownership: LongArray): NativeReply {
            val method = Json.parseToJsonElement(request.decodeToString()).jsonObject.getValue("method").jsonPrimitive.content
            abi.pendingRequests = hold && method != "call.list"
            abi.completionBody = if(method == "call.list") buildJsonObject { put("calls", buildJsonArray { add(row()) }) }
            else { state = "ended"; sequence++; row(if(wrongReply) "wrong-peer" else "peer") }
            return abi.submit(core, request, ownership)
        }
    }
    @Test fun realBridgeKeepsExactVersionsRechecksTargetAndDoesNotActivateAudio() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/call-list", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultCallDirectoryRepository(SdkRepositoryTransport { client })
            native.sequence = ULong.MAX_VALUE; native.generation = ULong.MAX_VALUE
            assertEquals(ULong.MAX_VALUE, (repo.list("account") as ClientResult.Ready).value.single().sequence)
            native.sequence = 7u
            val expected = (repo.list("account") as ClientResult.Ready).value.single()
            val count = native.abi.submitted.size
            assertTrue(repo.audioAvailability() is ClientResult.Unavailable)
            assertEquals(count, native.abi.submitted.size)
            native.sequence = 8u
            assertTrue(repo.signal("account", CallSignal("stale-op", expected, true)) is ClientResult.Failed)
            assertFalse(native.abi.submitted.any { it["method"]?.jsonPrimitive?.content != "call.list" })
            native.state = "ended"
            val actual = (repo.signal("account", CallSignal("already-ended", expected, true)) as ClientResult.Ready).value
            assertEquals("missed", actual.endReason)
            assertEquals(CallPhase.ENDED, actual.phase)
        } finally { client.close() }
    }
    @Test fun publishedTerminalReasonsSurviveHistorySignalAndReadbackThroughRealBridge() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/call-reasons", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultCallDirectoryRepository(SdkRepositoryTransport { client })
            for (reason in listOf("completed", "cancelled", "answered_elsewhere", "busy", "timeout", "failed")) {
                native.state = "incoming_ringing"; native.reason = reason
                val expected = (repo.list("account") as ClientResult.Ready).value.single()
                val result = repo.signal("account", CallSignal("end-${native.sequence}", expected, true)) as ClientResult.Ready
                assertEquals(reason, result.value.endReason)
                assertEquals(reason, (repo.list("account") as ClientResult.Ready).value.single().endReason)
            }
        } finally { client.close() }
    }
    @Test fun endSignalHasExactScopeAndMalformedAcknowledgementStaysUnknownUntilReadback() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/call-signal", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultCallDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.list("account") as ClientResult.Ready).value.single()
            native.wrongReply = true
            val attempt = CallSignal("decline-id", expected, true)
            assertEquals(ClientOutcome.UNKNOWN, (repo.signal("account", attempt) as ClientResult.Failed).problem.outcome)
            assertEquals(attempt, repo.pending("account"))
            val frame = native.abi.submitted.last()
            assertEquals("call.reject", frame["method"]?.jsonPrimitive?.content)
            assertEquals("account", frame["account_id"]?.jsonPrimitive?.content)
            assertEquals("decline-id", frame["operation_id"]?.jsonPrimitive?.content)
            assertEquals("call", frame["body"]?.jsonObject?.get("call_id")?.jsonPrimitive?.content)
            repo.list("account")
            assertNull(repo.pending("account"))
            assertFalse(native.abi.submitted.any { it["method"]?.jsonPrimitive?.content in setOf("call.invite", "call.answer", "call.media.session") })
        } finally { client.close() }
    }
    @Test fun admittedCancellationRetainsOriginalSignalAndClosesItsNativeWaiter() = runTest(timeout = 5.seconds) {
        val native = Oracle()
        val client = (CoreClient.open(native, "/test/call-cancel", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success).value
        try {
            val repo = DefaultCallDirectoryRepository(SdkRepositoryTransport { client })
            val expected = (repo.list("account") as ClientResult.Ready).value.single()
            val attempt = CallSignal("end-id", expected, false)
            native.hold = true
            val job = launch { repo.signal("account", attempt) }
            runCurrent()
            val core = native.abi.handles.single(); val operation = native.abi.client(core).operations.keys.single()
            native.abi.admit(core, operation); job.cancelAndJoin()
            assertEquals(attempt, repo.pending("account"))
            assertTrue(operation in native.abi.cancelled); assertTrue(operation in native.abi.released)
        } finally { client.close() }
    }
}
