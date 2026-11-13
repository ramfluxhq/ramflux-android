// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreClientTest {
    private val empty = JsonObject(emptyMap())
    private fun request() = CoreRequest("message.submit", "message", "account", "stable-key", empty)

    @Test fun cancelledWaiterCancelsAndReleasesSameNativeOperation() = runTest {
        val native = FakeNative()
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        val call = async { client.execute(request()) }
        runCurrent()
        call.cancelAndJoin()
        assertEquals(listOf(2L), native.cancelled)
        assertEquals(listOf(2L), native.released)
        assertEquals(1, native.submitted.size)
        assertEquals("stable-key", native.submitted.single()["operation_id"]?.jsonPrimitive?.content)
        client.close()
    }

    @Test fun cancelledBeforeAdmissionNeverSubmits() = runTest {
        val native = FakeNative()
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        val call = async { client.execute(request()) }
        call.cancelAndJoin()
        assertTrue(native.submitted.isEmpty())
        client.close()
    }

    @Test fun completedOperationReleasedWithoutCancellationOrResubmit() = runTest {
        val native = FakeNative().apply { terminal = true }
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        val result = client.execute(request())
        assertTrue(result is CoreResult.Success)
        assertTrue(native.cancelled.isEmpty())
        assertEquals(listOf(2L), native.released)
        assertEquals(1, native.submitted.size)
        client.close()
    }

    @Test fun closeReleasesOperationsBeforeClosingAndIsIdempotent() = runTest {
        val native = FakeNative()
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        val call = async { client.execute(request()) }
        runCurrent()
        client.close()
        advanceTimeBy(20)
        runCurrent()
        assertTrue(call.await() is CoreResult.Failure)
        assertEquals(listOf("cancel", "release", "close"), native.actions)
        client.close()
        assertEquals(1, native.closes)
    }

    @Test fun unknownEffectKeepsOperationIdentityAndWipesBuffer() {
        val bytes = """{"code":10,"effects":"unknown"}""".toByteArray()
        val result = ReplyCodec.decodeCompletion(NativeReply(0, bytes), "stable-key") as CoreResult.Failure
        assertEquals(Effect.UNKNOWN, result.error.effect)
        assertEquals("stable-key", result.operationId)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun unknownContentRemainsSuccessfulData() {
        val result = ReplyCodec.decodeCompletion(NativeReply(0,
            """{"code":0,"response":{"body":{"kind":"future-kind"}}}""".toByteArray()), "key")
        assertTrue(result is CoreResult.Success)
    }

    @Test fun streamCancellationReleasesSingleCollectorOwnership() = runTest {
        val native = FakeNative().apply { terminal = true }
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        repeat(2) {
            assertEquals(1, client.updates(SubscriptionFilter("account", empty)).take(1).toList().size)
            runCurrent()
        }
        client.close()
    }

    @Test fun eventOverflowRequiresResync() = runTest {
        val native = FakeNative().apply { terminal = true; overflow = true }
        val client = CoreClient(native, 1, StandardTestDispatcher(testScheduler))
        assertEquals(listOf(CoreUpdate.Terminal("resync_required", null)), client.updates(SubscriptionFilter("account", empty)).toList())
        client.close()
    }

    private class FakeNative : NativeCore {
        val submitted = mutableListOf<JsonObject>()
        val cancelled = mutableListOf<Long>()
        val released = mutableListOf<Long>()
        val actions = mutableListOf<String>()
        var terminal = false
        var overflow = false
        var closes = 0
        override fun negotiate() = NativeReply(0, """{"abi_major":2}""".toByteArray())
        override fun open(config: ByteArray) = NativeReply(0, byteArrayOf(), 1)
        override fun close(core: Long): NativeReply { closes++; actions.add("close"); return NativeReply(0, byteArrayOf()) }
        override fun submit(core: Long, request: ByteArray): NativeReply {
            submitted.add(kotlinx.serialization.json.Json.parseToJsonElement(request.decodeToString()).jsonObject)
            return NativeReply(0, byteArrayOf(), 2)
        }
        override fun operationPoll(core: Long, operation: Long) = if (terminal)
            NativeReply(0, """{"code":0,"response":{"body":{}}}""".toByteArray()) else NativeReply(24, byteArrayOf())
        override fun operationCancel(core: Long, operation: Long): Int { cancelled.add(operation); actions.add("cancel"); return 0 }
        override fun operationRelease(core: Long, operation: Long): Int { released.add(operation); actions.add("release"); return 0 }
        override fun eventReset(core: Long): Int { overflow = false; return 0 }
        override fun eventPoll(core: Long) = if (overflow) NativeReply(27, byteArrayOf()) else
            NativeReply(0, """{"kind":"update","sequence":"18446744073709551615"}""".toByteArray())
    }
}
