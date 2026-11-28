// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SecretTransportTest {
    @Test fun showOnceSourceAndDecodedCharactersAreWipedAndSecondAccessFails() {
        val source = """{"code":0,"response":{"body":{"phrase":"harbor \\u0061nchor"}}}""".toByteArray()
        val result = ReplyCodec.decodeSecret(NativeReply(0, source), null) as CoreResult.Success
        assertTrue(source.all { it == 0.toByte() })
        var borrowed: CharArray? = null
        result.value.consumeChars("phrase") { chars -> borrowed = chars; assertTrue(chars.isNotEmpty()) }
        assertTrue(borrowed!!.all { it == '\u0000' })
        assertThrows(IllegalStateException::class.java) { result.value.useOnce {} }
    }

    @Test fun omittedEffectsCarryCustodySetupInvalidInsteadOfUnknown() {
        val source = (
            """{"code":1,"response":{"kind":"error","method":"account.create.begin","ok":false,""" +
                """"error":{"code":"CustodySetupInvalid","message":"not-shown"}}}"""
            ).toByteArray()
        val result = ReplyCodec.decodeSecret(NativeReply(0, source), null) as CoreResult.Failure
        assertTrue(source.all { it == 0.toByte() })
        val error = result.error
        assertTrue(error is CoreError.InvalidArgument)
        assertEquals(1, error.code)
        assertEquals(Effect.NONE, error.effect)
        assertEquals("CustodySetupInvalid", error.statusName)
        assertEquals(null, error.reasonCode)
        assertFalse(error.toString().contains("not-shown"))
    }

    @Test fun confirmReasonCodesReachTheTypedErrorAndDropSecretText() {
        val reasons = listOf(
            "passphrase_empty",
            "passphrase_too_long",
            "passphrase_not_utf8",
            "display_name",
            "operation_id",
            "deadline",
            "word_8",
        )
        for (reason in reasons) {
            val status = when (reason) {
                "word_8" -> "CustodyConfirmationFailed"
                "deadline" -> "CustodyExpired"
                else -> "CustodySetupInvalid"
            }
            val number = when (reason) {
                "word_8" -> 5
                "deadline" -> 16
                else -> 1
            }
            val source = (
                """{"code":$number,"response":{"kind":"error","error":{"code":"$status","reason_code":"$reason","message":"harbor"}}}"""
            ).toByteArray()
            val decoded = ReplyCodec.decode(NativeReply(number, source), null) as CoreResult.Failure
            assertEquals(reason, decoded.error.reasonCode)
            assertEquals(status, decoded.error.statusName)
            assertFalse(decoded.error.toString().contains("harbor"))
        }
        val leaked = """{"code":1,"response":{"error":{"code":"CustodySetupInvalid","reason_code":"word harbor"}}}""".toByteArray()
        val dropped = ReplyCodec.decode(NativeReply(1, leaked), null) as CoreResult.Failure
        assertEquals(null, dropped.error.reasonCode)
    }

    @Test fun debugTraceRecordsSdkTextAndVariantWithoutPublishingThem() {
        val lines = mutableListOf<String>()
        installSdkDebugTrace { lines.add(it) }
        try {
            val source = (
                """{"code":1,"response":{"error":{"code":"ValidationFailed","variant":"Storage",""" +
                    """"message":"storage error: mismatch"}}}"""
                ).toByteArray()
            val decoded = ReplyCodec.decode(NativeReply(1, source), null) as CoreResult.Failure
            assertEquals("ValidationFailed", decoded.error.statusName)
            assertFalse(decoded.error.toString().contains("mismatch"))
            assertFalse(decoded.error.toString().contains("Storage"))
            assertEquals("sdk.failed.1.ValidationFailed.NONE variant=Storage text=storage error: mismatch", lines.single())
            val secret = (
                """{"code":1,"response":{"error":{"code":"ValidationFailed","message":"storage error: mismatch"}}}"""
                ).toByteArray()
            lines.clear()
            val hidden = ReplyCodec.decodeSecret(NativeReply(0, secret), null) as CoreResult.Failure
            assertEquals("ValidationFailed", hidden.error.statusName)
            assertFalse(hidden.error.toString().contains("mismatch"))
            assertEquals("sdk.failed.1.ValidationFailed.NONE variant=- text=storage error: mismatch", lines.single())
        } finally {
            installSdkDebugTrace(null)
        }
    }

    @Test fun unsetDebugTraceDropsSdkText() {
        installSdkDebugTrace(null)
        val source = """{"code":1,"response":{"error":{"code":"ValidationFailed","message":"storage error: mismatch","variant":"Storage"}}}""".toByteArray()
        val decoded = ReplyCodec.decodeSecret(NativeReply(0, source), null) as CoreResult.Failure
        assertEquals("ValidationFailed", decoded.error.statusName)
        assertFalse(decoded.error.toString().contains("mismatch"))
        assertFalse(decoded.error.toString().contains("Storage"))
    }

    @Test fun explicitUnknownEffectsStayUnknown() {
        val source = """{"code":1,"effects":"unknown","response":{"error":{"code":"CustodySetupInvalid"}}}""".toByteArray()
        val secret = ReplyCodec.decodeSecret(NativeReply(0, source.copyOf()), null) as CoreResult.Failure
        assertEquals(Effect.UNKNOWN, secret.error.effect)
        assertEquals("CustodySetupInvalid", secret.error.statusName)
        val ordinary = ReplyCodec.decode(NativeReply(1, source), null) as CoreResult.Failure
        assertEquals(Effect.UNKNOWN, ordinary.error.effect)
        assertEquals(1, ordinary.error.code)
    }

    @Test fun closingWithoutConsumptionWipesOwnedAllocation() {
        val owned = byteArrayOf(123, 125)
        val holder = SecretReply(SecretBytes.take(owned))
        holder.close(); holder.close()
        assertTrue(owned.all { it == 0.toByte() })
    }

    @Test fun confidentialRequestNeverUsesOrdinaryJsonPathAndWipesInput() = runTest(timeout = 5.seconds) {
        val native = FakeAbi().apply { completionBody = buildJsonObject { put("unlocked", true) } }
        val client = CoreClient(native, native.newClient(), StandardTestDispatcher(testScheduler))
        val passphrase = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        val input = SecretBody.fields { chars("passphrase", passphrase) }
        assertTrue(passphrase.all { it == '\u0000' })
        val result = client.executeSecret(SecretRequest("account.unlock", "account", "A", "original", input))
        assertTrue(result is CoreResult.Success)
        (result as CoreResult.Success).value.close()
        assertTrue(native.returnedBuffers.all { raw -> raw.all { it == 0.toByte() } })
        assertThrows(IllegalStateException::class.java) { input.storage.useOnce {} }
        client.close()
    }

    @Test fun generatedPolicyRequiresSecretPathForAllConfidentialMethods() {
        assertTrue(SdkMethodPolicy.methods.getValue("account.create.show_once").showOnce)
        assertTrue(SdkMethodPolicy.methods.getValue("account.export_agent_credentials").secretOutput)
        assertThrows(IllegalArgumentException::class.java) {
            SecretBody.publicJson("account.unlock", buildJsonObject { put("passphrase", "fixture-only") })
        }
    }
}
