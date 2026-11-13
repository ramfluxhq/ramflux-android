// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FakeAbiContractTest {
    private fun frame() = buildJsonObject {
        put("method", "node.list"); put("sdk_api", "node"); put("body", buildJsonObject {})
        put("deadline_ms", Long.MAX_VALUE)
    }
    private fun bytes(frame: JsonObject) = frame.toString().toByteArray()

    @Test fun malformedFramesAndRelativeRootsFailBeforeAdmission() {
        val abi = FakeAbi(); val id = abi.newClient()
        val valid = frame()
        val invalid = listOf(
            JsonObject(valid - "sdk_api"), JsonObject(valid - "deadline_ms"),
            JsonObject(valid + ("deadline_ms" to JsonPrimitive(-1))),
            JsonObject(valid + ("deadline_ms" to JsonPrimitive("100"))),
            JsonObject(valid + ("deadline_ms" to JsonPrimitive(1.5))),
            JsonObject(valid + ("unknown" to JsonPrimitive(true))),
        )
        for (request in invalid) assertEquals(1, abi.submit(id, bytes(request)).code)
        assertTrue(abi.submitted.isEmpty()); assertTrue(abi.client(id).operations.isEmpty())
        assertEquals(1, abi.open("""{"data_root":"relative"}""".toByteArray()).code)
        assertEquals(1, abi.open("""{"data_root":"/root","unknown":true}""".toByteArray()).code)
        assertEquals(1, abi.liveWriters)
        abi.close(id)
    }

    @Test fun expiredDeadlineProducesTerminalTimeoutWithNoEffect() {
        val abi = FakeAbi(); val id = abi.newClient()
        val operation = abi.submit(id, bytes(JsonObject(frame() + ("deadline_ms" to JsonPrimitive(1)))))
        assertEquals(0, operation.code)
        val reply = ReplyCodec.decodeCompletion(abi.operationPoll(id, operation.handle), null) as CoreResult.Failure
        assertEquals(10, reply.error.code); assertEquals(Effect.NONE, reply.error.effect)
        assertEquals(0, abi.operationRelease(id, operation.handle)); abi.close(id)
    }

    @Test fun retainedClosedCarrierCompletesPendingOperationsButUnknownHandlesAreInvalid() {
        val abi = FakeAbi().apply { pendingRequests = true }; val id = abi.newClient()
        val operation = abi.submit(id, bytes(frame())).handle
        abi.closeTimeout = true
        assertEquals(10, abi.close(id).code)
        assertEquals(9, abi.submit(id, bytes(frame())).code)
        assertEquals(24, abi.close(id).code)
        abi.completeShutdown(id)
        val terminal = ReplyCodec.decodeCompletion(abi.operationPoll(id, operation), null) as CoreResult.Failure
        assertEquals(26, terminal.error.code); assertEquals(Effect.UNKNOWN, terminal.error.effect)
        assertEquals(26, abi.eventPoll(id).code)
        assertEquals(0, abi.operationRelease(id, operation))
        abi.closeTimeout = false; assertEquals(0, abi.close(id).code)
        assertEquals(1, abi.submit(id, bytes(frame())).code)
        assertEquals(1, abi.eventPoll(id).code)
        assertEquals(1, abi.operationPoll(id, operation).code)
        assertEquals(1, abi.operationCancel(id, operation))
        assertEquals(1, abi.operationRelease(id, operation))
        assertEquals(1, abi.eventReset(id, 0))
    }
}
