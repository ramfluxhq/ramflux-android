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
        assertTrue(SdkMethodPolicy.methods.getValue("account.backup.export").secretOutput)
        assertTrue(SdkMethodPolicy.methods.getValue("account.export_agent_credentials").secretOutput)
        assertThrows(IllegalArgumentException::class.java) {
            SecretBody.publicJson("account.unlock", buildJsonObject { put("passphrase", "fixture-only") })
        }
    }
}
