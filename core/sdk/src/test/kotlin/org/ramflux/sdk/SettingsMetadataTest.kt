// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk
import org.junit.Assert.*
import org.junit.Test
class SettingsMetadataTest {
    @Test fun onlyDeviceFactsAreMaterializedAndRawBytesAreWiped() {
        val raw = """{"devices":[{"device_id":"local","is_local":true,"device_epoch":18446744073709551615,"target_delivery_id":"do-not-copy"}]}""".toByteArray()
        val rows = (SettingsMetadataReader.devices(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertEquals(listOf(DeviceMetadata("local", true, ULong.MAX_VALUE)), rows)
        assertFalse(rows.toString().contains("do-not-copy")); assertTrue(raw.all { it == 0.toByte() })
    }
    @Test fun nullSamplesAreDistinctFromMalformedOrInventedConnectedState() {
        val raw = """{"secure_sessions":"ready","sync_status":null,"node_health":null,"last_run":null}""".toByteArray()
        val state = (SettingsMetadataReader.sync(SecretReply(SecretBytes.take(raw))) as CoreResult.Success).value
        assertNull(state.sync); assertNull(state.node); assertNull(state.lastRun); assertTrue(raw.all { it == 0.toByte() })
        val invalid = """{"secure_sessions":"ready"}""".toByteArray()
        assertTrue(SettingsMetadataReader.sync(SecretReply(SecretBytes.take(invalid))) is CoreResult.Failure)
        assertTrue(invalid.all { it == 0.toByte() })
    }
}
