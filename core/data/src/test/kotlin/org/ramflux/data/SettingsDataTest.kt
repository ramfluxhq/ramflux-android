// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.sdk.*
class SettingsDataTest {
    @Test fun actualBridgeKeepsDeviceLocalFlagAndRevokesThroughTheDeviceMethod() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject { put("devices", buildJsonArray { add(buildJsonObject {
            put("device_id", "device"); put("device_epoch", 7); put("is_local", true)
        }) }) } }
        val client = (CoreClient.open(abi, "/test/settings-devices", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultDeviceDirectoryRepository(SdkRepositoryTransport { client })
            assertEquals(listOf(DeviceSummary("device", true, 7u)), (repo.list("account") as ClientResult.Ready).value)
            assertEquals("device.list", abi.submitted.single()["method"]?.jsonPrimitive?.content)
            assertEquals("account", abi.submitted.single()["account_id"]?.jsonPrimitive?.content)
            abi.completionBody = buildJsonObject { put("revoked", true) }
            assertTrue(repo.revoke("account", "other-device") is ClientResult.Ready)
            val revoke = abi.submitted.last()
            assertEquals("device.revoke", revoke["method"]?.jsonPrimitive?.content)
            assertEquals("other-device", revoke["body"]?.jsonObject?.get("device_id")?.jsonPrimitive?.content)
            assertNotNull("a revoke carries an operation id", revoke["operation_id"])
            abi.completionCode = 12
            assertTrue(repo.revoke("account", "other-device") is ClientResult.Failed)
        } finally { client.close() }
    }
    @Test fun nativeNoSampleIsNotConnectedAndMalformedIsTypedFailure() = runTest(timeout = 5.seconds) {
        val abi = FakeAbi().apply { completionBody = buildJsonObject {
            put("secure_sessions", "ready"); put("sync_status", JsonNull); put("node_health", JsonNull); put("last_run", JsonNull)
        } }
        val client = (CoreClient.open(abi, "/test/settings-status", "a".repeat(64)) as CoreResult.Success).value
        try {
            val repo = DefaultAccountStatusRepository(SdkRepositoryTransport { client })
            assertEquals(AccountStatus(SecureSessions.READY, SyncPhase.NOT_RECORDED, NodeHealth.NOT_RECORDED, null), (repo.read("account") as ClientResult.Ready).value)
            abi.completionBody = buildJsonObject { put("secure_sessions", "ready") }
            assertTrue(repo.read("account") is ClientResult.Failed)
        } finally { client.close() }
    }
}
