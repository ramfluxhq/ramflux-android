// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.serialization.json.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.time.Duration.Companion.seconds

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
        val metadata = Json.parseToJsonElement(FakeAbi().negotiate().body.decodeToString()) as JsonObject
        assertNotNull(CoreCapabilities.parse(metadata, "a".repeat(64)))
        assertNull(CoreCapabilities.parse(metadata, "b".repeat(64)))
        assertNull(CoreCapabilities.parse(metadata, ""))
    }

    @Test fun changedOrIncompleteNegotiatedPolicyRefusesOpenBeforeCreatingClient() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val original = abi.policyManifest()
        val rows = original.getValue("methods") as JsonArray
        val first = rows.first() as JsonObject
        val variants = listOf(
            JsonObject(original - "policy_version"),
            JsonObject(original + ("policy_version" to JsonPrimitive(1))),
            JsonObject(original + ("methods" to JsonArray(rows.drop(1)))),
            JsonObject(original + ("methods" to JsonArray(rows.dropLast(1) + rows.first()))),
        ) + listOf("secret_input", "secret_output", "show_once", "key_export", "bridge_allowed").map { flag ->
            val changed = JsonObject(first + (flag to JsonPrimitive(!(first.getValue(flag) as JsonPrimitive).boolean)))
            JsonObject(original + ("methods" to JsonArray(listOf(changed) + rows.drop(1))))
        } + JsonObject(original + ("methods" to JsonArray(listOf(JsonObject(first + ("capability" to JsonPrimitive("changed")))) + rows.drop(1))))
        for (variant in variants) {
            abi.negotiatedPolicy = variant
            val result = CoreClient.open(abi, "/test/policy", "a".repeat(64), StandardTestDispatcher(testScheduler))
            assertTrue(result is CoreResult.Failure)
            assertEquals(CoreError.MethodPolicyMismatch, (result as CoreResult.Failure).error)
            assertEquals("fail before opening a native writer", 0, abi.opens)
        }
        val missing = JsonObject(Json.parseToJsonElement(abi.negotiate().body.decodeToString()).jsonObject - "method_policy")
        assertNull(CoreCapabilities.parse(missing, "a".repeat(64)))
        abi.negotiatedPolicy = original
        val opened = CoreClient.open(abi, "/test/policy", "a".repeat(64), StandardTestDispatcher(testScheduler)) as CoreResult.Success
        opened.value.close()
    }

    @Test fun negotiatedOperationCreditCannotExceedPreallocatedOwnershipCapacity() {
        val metadata = Json.parseToJsonElement(FakeAbi().negotiate().body.decodeToString()).jsonObject
        assertNotNull(CoreCapabilities.parse(metadata, "a".repeat(64)))
        val changed = JsonObject(metadata + ("max_operations" to JsonPrimitive(65)))
        assertNull(CoreCapabilities.parse(changed, "a".repeat(64)))
    }

    @Test fun generationTagsUseExactCanonicalUnsigned64BitIntegers() {
        val maximum = """{"subscription_generation":"18446744073709551615"}""".toByteArray()
        assertEquals(ULong.MAX_VALUE, ReplyCodec.eventGeneration(maximum))
        for (invalid in listOf("18446744073709551616", "01", "+1", "-1", "1.0")) {
            val frame = "{\"subscription_generation\":\"$invalid\"}".toByteArray()
            assertNull(ReplyCodec.eventGeneration(frame))
        }
    }

    @Test fun requestLoggingRedactsSecretBody() {
        val request = CoreRequest("account.unlock", "account", "account", "key", buildJsonObject { put("passphrase", "private-secret") })
        assertFalse(request.toString().contains("private-secret"))
        assertFalse(NativeReply(0, "private-secret".toByteArray()).toString().contains("private-secret"))
    }
}
