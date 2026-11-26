// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*

class AppLockReaderTest {
    private fun rows(vararg states: String) = buildJsonObject {
        put("account_details", buildJsonArray {
            states.forEachIndexed { i, s -> add(buildJsonObject { put("id", "acc$i"); put("display_name", "A$i"); put("state", s) }) }
        })
        put("active_account_id", JsonNull)
    }
    private fun lock(enabled: Boolean, state: String, reason: String = "authenticated") = buildJsonObject {
        put("enabled", enabled); put("timeout_ms", 0); put("state", state); put("reason", reason)
        put("remaining_ms", JsonNull); put("presence_available", false); put("generation", 1)
    }

    private suspend fun read(list: JsonObject, state: JsonObject?): AppLockReading {
        val abi = FakeAbi().apply {
            bodyFor = { if (it == "account.list") list else state }
            completionBody = state ?: buildJsonObject {}
        }
        val client = (CoreClient.open(abi, "/test/app-lock-reader", "a".repeat(64)) as CoreResult.Success).value
        try { return AppLockReader(SdkRepositoryTransport { client }).read() } finally { client.close() }
    }

    @Test fun lockedRowWithTheLockOffIsNotCovered() = runTest(timeout = 5.seconds) {
        val reading = read(rows("locked"), lock(false, "unlocked"))
        assertEquals(AppLockPhase.OPEN, reading.phase)
        assertFalse(reading.foreground)
    }

    @Test fun lockedRowWithTheLockOnIsCoveredAndNamed() = runTest(timeout = 5.seconds) {
        val reading = read(rows("locked"), lock(true, "locked", "cold_start"))
        assertEquals(AppLockPhase.LOCKED, reading.phase)
        assertEquals("acc0", reading.account)
    }

    @Test fun unreadableStateOfALockedRowFailsClosed() = runTest(timeout = 5.seconds) {
        assertEquals(AppLockPhase.UNKNOWN, read(rows("locked"), buildJsonObject { put("state", "bogus") }).phase)
    }

    @Test fun restorePendingRowCoversOnlyWhenItsLockIsOn() = runTest(timeout = 5.seconds) {
        // The pending row never settles in this fake, so the bounded wait ends and the per-row rule applies.
        assertEquals(AppLockPhase.OPEN, read(rows("restore_pending"), lock(false, "unlocked")).phase)
        assertEquals(AppLockPhase.UNKNOWN, read(rows("restore_pending"), lock(true, "locked", "cold_start")).phase)
    }

    @Test fun foregroundIsNeededOnlyAfterAnUnlockedAccountWasRead() = runTest(timeout = 5.seconds) {
        assertTrue(read(rows("unlocked"), lock(false, "unlocked")).foreground)
        assertFalse(read(buildJsonObject { put("account_details", buildJsonArray {}); put("active_account_id", JsonNull) }, null).foreground)
    }
}
