// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class WireContractTest {
    @Test fun deepNativeReplyIsRejectedBeforeRecursiveParsing() {
        val bytes = ("{\"value\":".repeat(40) + "null" + "}".repeat(40)).toByteArray()
        val result = ReplyCodec.decode(NativeReply(0, bytes))
        assertTrue(result is CoreResult.Failure)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun malformedCompletionCodeIsTypedFailure() {
        val result = ReplyCodec.decodeCompletion(NativeReply(0, """{"code":{},"response":{}}""".toByteArray()), "original-key")
        assertTrue(result is CoreResult.Failure)
    }

    @Test fun unverifiedDescriptorFailsNegotiation() {
        val metadata = Json.parseToJsonElement("""{
            "abi_major":2,"abi_minor":0,"bus_protocol":"ramflux.local_bus.v1",
            "max_frame_bytes":1048576,"message_descriptor_blake3":"${"a".repeat(64)}",
            "methods":["account.list"]
        }""") as JsonObject
        assertNotNull(CoreCapabilities.parse(metadata, "a".repeat(64)))
        assertNull(CoreCapabilities.parse(metadata, "b".repeat(64)))
        assertNull(CoreCapabilities.parse(metadata, ""))
    }

    @Test fun requestLoggingRedactsSecretBody() {
        val request = CoreRequest("account.unlock", "account", "account", "key", buildJsonObject { put("passphrase", "private-secret") })
        assertFalse(request.toString().contains("private-secret"))
        assertFalse(NativeReply(0, "private-secret".toByteArray()).toString().contains("private-secret"))
    }
}
