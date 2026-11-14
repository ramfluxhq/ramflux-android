// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.sdk.CoreClient
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.FakeAbi

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountCeremonyDataTest {
    @Test fun creationCeremonyUsesShowOnceThenChallengeWithoutASecondPhraseCopy() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/ceremony", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountCeremonyRepository(SdkRepositoryTransport { client })
            assertTrue(repo.beginCreate(" ", null) is ClientResult.Failed)
            assertTrue(abi.submitted.isEmpty())
            abi.completionBody = buildJsonObject {
                put("ceremony", "pending")
                put("ceremony_id", "ceremony-test")
                put("expires_in_ms", 300000)
            }
            val pending = repo.beginCreate("Ada", "node-1") as ClientResult.Ready
            assertEquals("ceremony-test", pending.value.id)
            val begin = abi.submitted.single()
            assertEquals("account.create.begin", begin.getValue("method").toString().trim('"'))
            val body = begin.getValue("body") as JsonObject
            assertEquals("Ada", body.getValue("display_name").toString().trim('"'))
            assertEquals("human", body.getValue("principal_kind").toString().trim('"'))
            assertEquals("node-1", body.getValue("node_id").toString().trim('"'))
            abi.completionBody = buildJsonObject { put("phrase", (1..23).joinToString(" ") { "w$it" }) }
            val short = repo.showPhrase()
            assertTrue(short is ClientResult.Failed)
            abi.completionBody = buildJsonObject { put("phrase", (1..24).joinToString(" ") { "w$it" }) }
            val phrase = repo.showPhrase() as ClientResult.Ready
            assertEquals(24, phrase.value.length)
            assertEquals("RecoveryPhrase(<redacted>)", phrase.value.toString())
            phrase.value.draw(0) { word -> assertEquals(2, word.size) }
            phrase.value.close()
            abi.completionBody = buildJsonObject {
                put("positions", buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)); add(JsonPrimitive(24)) })
            }
            val positions = repo.challenge() as ClientResult.Ready
            assertEquals(3, positions.value.size)
            assertEquals(24, positions.value[2])
            abi.completionBody = buildJsonObject { put("account_id", "account-1"); put("display_name", "Ada") }
            val words = arrayOf("w1".toCharArray(), "w2".toCharArray(), "w24".toCharArray())
            val passphrase = "seal".toCharArray()
            val created = repo.confirm(words, passphrase) as ClientResult.Ready
            assertEquals("account-1", created.value)
            assertTrue(words.all { word -> word.all { it == '\u0000' } })
            assertTrue(passphrase.all { it == '\u0000' })
            assertEquals(
                listOf("account.create.begin", "account.create.show_once", "account.create.show_once", "account.create.hidden_challenge", "account.create.confirm"),
                abi.submitted.map { it.getValue("method").toString().trim('"') },
            )
        } finally {
            client.close()
        }
    }

    @Test fun cancelClearsOnlyAfterTheDaemonAcknowledges() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/ceremony-cancel", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountCeremonyRepository(SdkRepositoryTransport { client })
            abi.completionBody = buildJsonObject {
                put("ceremony", "pending"); put("ceremony_id", "ceremony-cancel"); put("expires_in_ms", 1000)
            }
            assertTrue(repo.beginCreate("Ada", null) is ClientResult.Ready)
            abi.completionBody = buildJsonObject { put("cancelled", false) }
            assertTrue(repo.cancel() is ClientResult.Failed)
            abi.completionBody = buildJsonObject { put("cancelled", true) }
            assertTrue(repo.cancel() is ClientResult.Ready)
            assertTrue(repo.cancel() is ClientResult.Ready)
            assertEquals(2, abi.submitted.count { it.getValue("method").toString().contains("cancel") })
        } finally {
            client.close()
        }
    }

    @Test fun failedCancelKeepsTheCeremonyUntilTheDaemonAcknowledges() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/ceremony-cancel-fail", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountCeremonyRepository(SdkRepositoryTransport { client })
            abi.completionBody = buildJsonObject {
                put("ceremony", "pending"); put("ceremony_id", "ceremony-fail"); put("expires_in_ms", 1000)
            }
            assertTrue(repo.beginCreate("Ada", null) is ClientResult.Ready)
            abi.completionCode = 12
            assertTrue(repo.cancel() is ClientResult.Failed)
            val before = abi.submitted.size
            assertTrue(repo.beginCreate("Ada", null) is ClientResult.Failed)
            assertEquals(before, abi.submitted.size)
            abi.completionCode = 0
            abi.completionBody = buildJsonObject { put("cancelled", true) }
            assertTrue(repo.cancel() is ClientResult.Ready)
            assertEquals(2, abi.submitted.count { it.getValue("method").toString().contains("cancel") })
            assertTrue(repo.cancel() is ClientResult.Ready)
            assertEquals(2, abi.submitted.count { it.getValue("method").toString().contains("cancel") })
        } finally {
            client.close()
        }
    }

    @Test fun cancelledCancelCallKeepsTheCeremonyForALaterAttempt() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/ceremony-cancel-job", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountCeremonyRepository(SdkRepositoryTransport { client })
            abi.completionBody = buildJsonObject {
                put("ceremony", "pending"); put("ceremony_id", "ceremony-job"); put("expires_in_ms", 1000)
            }
            assertTrue(repo.beginCreate("Ada", null) is ClientResult.Ready)
            val seen = CompletableDeferred<Unit>()
            abi.pendingRequests = true
            abi.onSubmitted = { frame ->
                if (frame.getValue("method").toString().contains("cancel")) seen.complete(Unit)
            }
            val job = launch(Dispatchers.IO) { repo.cancel() }
            seen.await()
            assertEquals(1, abi.submitted.count { it.getValue("method").toString().contains("cancel") })
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            val before = abi.submitted.size
            assertTrue(repo.beginCreate("Ada", null) is ClientResult.Failed)
            assertEquals(before, abi.submitted.size)
            abi.pendingRequests = false
            abi.onSubmitted = null
            abi.completionBody = buildJsonObject { put("cancelled", true) }
            assertTrue(repo.cancel() is ClientResult.Ready)
            assertEquals(2, abi.submitted.count { it.getValue("method").toString().contains("cancel") })
        } finally {
            client.close()
        }
    }
}
