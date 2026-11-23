// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.ramflux.sdk.CoreClient
import org.ramflux.sdk.CoreResult
import org.ramflux.sdk.FakeAbi

class KnownAccountsDataTest {
    private fun listing(vararg rows: Triple<String, String?, String>) = buildJsonObject {
        put(
            "account_details",
            buildJsonArray {
                for ((id, label, state) in rows) {
                    add(
                        buildJsonObject {
                            put("id", id)
                            if (label != null) put("label", label)
                            put("state", state)
                        }
                    )
                }
            }
        )
        put("active_account_id", JsonNull)
    }

    @Test fun fileRegistryKeepsPublicFactsAcrossInstancesAndReadsDamageAsEmpty() {
        val dir = Files.createTempDirectory("known").toFile()
        try {
            val file = File(dir, "known-accounts.json")
            val first = FileKnownAccountRegistry(file)
            first.record(KnownAccount("a", "Ada", "http://127.0.0.1:8482", 7))
            first.record(KnownAccount("b", "Bea"))
            first.record(KnownAccount("a", "Ada Lovelace"))
            first.record(KnownAccount("", "nobody"))
            val reread = FileKnownAccountRegistry(file).all()
            assertEquals(listOf("a", "b"), reread.map { it.id })
            assertEquals("Ada Lovelace", reread[0].displayName)
            assertEquals("a rename keeps the origin and creation time", "http://127.0.0.1:8482", reread[0].nodeOrigin)
            assertEquals(7L, reread[0].createdAtMillis)
            FileKnownAccountRegistry(file).remove("a")
            assertEquals(listOf("b"), FileKnownAccountRegistry(file).all().map { it.id })
            file.writeText("{not json")
            assertTrue(FileKnownAccountRegistry(file).all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun registryIsBoundedAndDropsTheOldestEntries() {
        val registry = MemoryKnownAccountRegistry()
        repeat(BoundedKnownAccountRegistry.MAX_ENTRIES + 3) { registry.record(KnownAccount("id-$it", "n$it")) }
        val ids = registry.all().map { it.id }
        assertEquals(BoundedKnownAccountRegistry.MAX_ENTRIES, ids.size)
        assertEquals("id-3", ids.first())
    }

    @Test fun lockedRegistryAccountsAreOfferedByNameAndOpenOnesKeepTheSdkState() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = listing(Triple("open", null, "unlocked")) }
        val client = (CoreClient.open(abi, "/test/known", "a".repeat(64)) as CoreResult.Success).value
        try {
            val registry = MemoryKnownAccountRegistry(listOf(KnownAccount("open", "Ada"), KnownAccount("closed", "Bea")))
            val repo = DefaultAccountAccessRepository(SdkRepositoryTransport { client }, registry)
            val rows = (repo.list() as ClientResult.Ready).value.accounts
            assertEquals(listOf("open", "closed"), rows.map { it.id })
            assertEquals("the header name comes from the registry when the SDK has no label", "Ada", rows[0].label)
            assertTrue(rows[0].unlocked && rows[0].listed && rows[0].known)
            assertEquals("Bea", rows[1].label)
            assertFalse(rows[1].unlocked || rows[1].listed)
            assertEquals("locked", rows[1].state)
            assertTrue("a registry offer alone never opens an account", repo.select("closed") is ClientResult.Unavailable)
            assertTrue("an open account cannot be forgotten", repo.forget("open") is ClientResult.Failed)
            assertTrue(repo.forget("closed") is ClientResult.Ready)
            assertEquals(listOf("open"), registry.all().map { it.id })
        } finally {
            client.close()
        }
    }

    @Test fun unlockingByCodeRecordsTheAccountWithTheSdkLabel() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        abi.onSubmitted = { frame ->
            abi.completionBody = when (frame.getValue("method").jsonPrimitive.content) {
                "account.list" -> listing(Triple("a", "Work", "unlocked"))
                else -> JsonObject(emptyMap())
            }
        }
        val client = (CoreClient.open(abi, "/test/known-unlock", "a".repeat(64)) as CoreResult.Success).value
        try {
            val registry = MemoryKnownAccountRegistry()
            val repo = DefaultAccountAccessRepository(SdkRepositoryTransport { client }, registry)
            assertTrue(repo.unlock("a", "482915".toCharArray()) is ClientResult.Ready)
            assertEquals(listOf(KnownAccount("a", "Work", null, registry.all().single().createdAtMillis)), registry.all())
            abi.completionCode = 8
            assertTrue(repo.unlock("b", "739164".toCharArray()) is ClientResult.Failed)
            assertNull("a refused unlock records nothing", registry.all().firstOrNull { it.id == "b" })
        } finally {
            client.close()
        }
    }

    @Test fun creationRecordsTheAccountWithItsNodeOrigin() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi()
        val client = (CoreClient.open(abi, "/test/known-create", "a".repeat(64)) as CoreResult.Success).value
        try {
            val registry = MemoryKnownAccountRegistry()
            val nodes = object : NodeSetupRepository {
                override suspend fun list() = ClientResult.Ready(listOf(SavedNode("node-1", "http://127.0.0.1:8482", "k", false, false, true)))
                override suspend fun discover(address: String): ClientResult<DiscoveredNode> = error("unused")
                override suspend fun confirm(candidate: DiscoveredNode, operatorKey: String): ClientResult<Unit> = error("unused")
                override suspend fun select(id: String): ClientResult<Unit> = error("unused")
            }
            val repo = DefaultAccountCeremonyRepository(SdkRepositoryTransport { client }, registry, nodes)
            abi.completionBody = buildJsonObject {
                put("ceremony", "pending")
                put("ceremony_id", "ceremony-test")
                put("expires_in_ms", 300000)
            }
            assertTrue(repo.beginCreate("Ada", "node-1") is ClientResult.Ready)
            abi.completionBody = buildJsonObject { put("account_id", "account-1"); put("display_name", "Ada") }
            val words = arrayOf("w1".toCharArray(), "w2".toCharArray(), "w24".toCharArray())
            assertTrue(repo.confirm(words, "482915".toCharArray()) is ClientResult.Ready)
            val entry = registry.all().single()
            assertEquals("account-1", entry.id)
            assertEquals("Ada", entry.displayName)
            assertEquals("http://127.0.0.1:8482", entry.nodeOrigin)
        } finally {
            client.close()
        }
    }
}
