// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.sdk.vault.CustodyCall
import org.ramflux.sdk.vault.CustodyFailure
import org.ramflux.sdk.vault.HostCustodyBridge
import org.ramflux.sdk.vault.HostCustodySession
import org.ramflux.sdk.vault.KeystoreBridge

/** keyLevel returns -KEY_MISSING after the software key is deleted. */
private const val DELETED_KEY_LEVEL = -2

/**
 * Software emulators run the refusal assertions. Hardware mode is the instrumentation
 * argument `custodyMode=hardware` and must complete store, load, rotate, delete,
 * invalidation, and reinstall. UNAVAILABLE is not a pass in that mode.
 */
private fun hardwareMode(): Boolean = InstrumentationRegistry.getArguments().getString("custodyMode") == "hardware"

private fun session(): HostCustodySession = HostCustodyBridge.open(InstrumentationRegistry.getInstrumentation().targetContext)

private fun assertSoftwareRefusal(stored: CustodyCall) {
    assertEquals(CustodyFailure.UNAVAILABLE, stored.failure)
    assertEquals(6, stored.status)
    assertEquals(DELETED_KEY_LEVEL, KeystoreBridge.keyLevel(0))
}

private fun assertHardwareStored(stored: CustodyCall) {
    assertTrue(stored.failure != CustodyFailure.UNAVAILABLE)
    assertEquals(0, stored.status)
    assertNull(stored.failure)
}

@RunWith(AndroidJUnit4::class)
class CustodyEmulatorTest {
    @Test fun storeLoadRotateDeleteAndKeyInvalidation() {
        val active = session()
        if (!hardwareMode()) {
            val stored = active.call(
                HostCustodySession.STORE_PRIMARY,
                "custody-software-refusal".toByteArray(),
                ByteArray(32) { it.toByte() },
                ByteArray(32)
            )
            Log.i("RamfluxCustody", "STORE level=${KeystoreBridge.keyLevel(0)} status=${stored.status} failure=${stored.failure}")
            assertSoftwareRefusal(stored)
            return
        }
        val reference = "hw-${UUID.randomUUID()}".toByteArray()
        val output = ByteArray(64)
        try {
            assertEquals(0, active.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
            val seed = ByteArray(32) { it.toByte() }
            val stored = active.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output)
            Log.i("RamfluxCustody", "STORE level=${KeystoreBridge.keyLevel(0)} status=${stored.status} failure=${stored.failure}")
            assertHardwareStored(stored)
            output.fill(0)
            val loaded = active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
            assertEquals(0, loaded.status)
            assertTrue(output.copyOf(32).contentEquals(seed))
            assertEquals(0, active.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
            assertEquals(
                CustodyFailure.MISSING,
                active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).failure
            )
            val rotated = ByteArray(32) { (it + 3).toByte() }
            assertHardwareStored(active.call(HostCustodySession.STORE_PRIMARY, reference, rotated.copyOf(), output))
            output.fill(0)
            assertEquals(0, active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).status)
            assertTrue(output.copyOf(32).contentEquals(rotated))
            assertEquals(0, active.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
            assertHardwareStored(active.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output))
            assertNull(HostCustodyBridge.deleteWrappingKey())
            val invalidated = active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
            assertEquals(CustodyFailure.KEY_INVALIDATED, invalidated.failure)
            assertEquals(2, invalidated.status)
        } finally {
            active.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0))
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CustodyReinstallSeedTest {
    @Test fun storesAProbeBeforeUninstall() {
        val active = session()
        val reference = "reinstall-probe".toByteArray()
        assertEquals(0, active.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
        val stored = active.call(HostCustodySession.STORE_PRIMARY, reference, ByteArray(32) { 9 }, ByteArray(32))
        Log.i("RamfluxCustody", "REINSTALL_SEED status=${stored.status} failure=${stored.failure}")
        if (hardwareMode()) assertHardwareStored(stored) else assertSoftwareRefusal(stored)
    }
}

@RunWith(AndroidJUnit4::class)
class CustodyReinstallAbsentTest {
    @Test fun loadAfterReinstallIsMissingThenANewStoreCanProceed() {
        val active = session()
        val reference = "reinstall-probe".toByteArray()
        val output = ByteArray(32)
        val missing = active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
        assertEquals(CustodyFailure.MISSING, missing.failure)
        val seed = ByteArray(32) { 4 }
        val stored = active.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output)
        Log.i("RamfluxCustody", "REINSTALL_STORE status=${stored.status} failure=${stored.failure}")
        if (!hardwareMode()) {
            assertSoftwareRefusal(stored)
            return
        }
        assertHardwareStored(stored)
        output.fill(0)
        assertEquals(0, active.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).status)
        assertTrue(output.contentEquals(seed))
    }
}
