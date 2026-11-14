// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.sdk.vault.CustodyFailure
import org.ramflux.sdk.vault.HostCustodyBridge
import org.ramflux.sdk.vault.HostCustodySession
import org.ramflux.sdk.vault.KeystoreBridge

/** Real Android Keystore. A software-only emulator refuses the wrap; it does not gain a software vault. */
@RunWith(AndroidJUnit4::class)
class CustodyEmulatorTest {
    @Test fun storeLoadRotateDeleteAndKeyInvalidation() {
        val session = HostCustodyBridge.open(InstrumentationRegistry.getInstrumentation().targetContext)
        val reference = "custody-roundtrip".toByteArray()
        val seed = ByteArray(32) { it.toByte() }
        val output = ByteArray(64)
        val stored = session.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output)
        Log.i("RamfluxCustody", "STORE level=${KeystoreBridge.keyLevel(0)} status=${stored.status} failure=${stored.failure}")
        if (stored.failure == CustodyFailure.UNAVAILABLE) {
            assertEquals(6, stored.status)
            return
        }
        assertEquals(0, stored.status)
        output.fill(0)
        val loaded = session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
        assertEquals(0, loaded.status)
        assertTrue(output.copyOf(32).contentEquals(seed))
        assertEquals(0, session.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
        assertEquals(CustodyFailure.MISSING, session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).failure)
        val rotated = ByteArray(32) { (it + 3).toByte() }
        assertEquals(0, session.call(HostCustodySession.STORE_PRIMARY, reference, rotated.copyOf(), output).status)
        output.fill(0)
        assertEquals(0, session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).status)
        assertTrue(output.copyOf(32).contentEquals(rotated))
        assertEquals(0, session.call(HostCustodySession.DELETE, reference, ByteArray(0), ByteArray(0)).status)
        assertEquals(0, session.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output).status)
        assertNull(HostCustodyBridge.deleteWrappingKey())
        val invalidated = session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
        assertEquals(CustodyFailure.KEY_INVALIDATED, invalidated.failure)
        assertEquals(2, invalidated.status)
    }
}

@RunWith(AndroidJUnit4::class)
class CustodyReinstallSeedTest {
    @Test fun storesAProbeBeforeUninstall() {
        val session = HostCustodyBridge.open(InstrumentationRegistry.getInstrumentation().targetContext)
        val stored = session.call(
            HostCustodySession.STORE_PRIMARY,
            "reinstall-probe".toByteArray(),
            ByteArray(32) { 9 },
            ByteArray(32)
        )
        Log.i("RamfluxCustody", "REINSTALL_SEED status=${stored.status} failure=${stored.failure}")
        if (stored.failure == CustodyFailure.UNAVAILABLE) assertEquals(6, stored.status) else assertEquals(0, stored.status)
    }
}

@RunWith(AndroidJUnit4::class)
class CustodyReinstallAbsentTest {
    @Test fun loadAfterReinstallIsMissingThenANewStoreCanProceed() {
        val session = HostCustodyBridge.open(InstrumentationRegistry.getInstrumentation().targetContext)
        val reference = "reinstall-probe".toByteArray()
        val output = ByteArray(32)
        val missing = session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output)
        assertEquals(CustodyFailure.MISSING, missing.failure)
        val seed = ByteArray(32) { 4 }
        val stored = session.call(HostCustodySession.STORE_PRIMARY, reference, seed.copyOf(), output)
        Log.i("RamfluxCustody", "REINSTALL_STORE status=${stored.status} failure=${stored.failure}")
        if (stored.failure == CustodyFailure.UNAVAILABLE) {
            assertEquals(6, stored.status)
            return
        }
        assertEquals(0, stored.status)
        output.fill(0)
        assertEquals(0, session.call(HostCustodySession.LOAD_PRIMARY, reference, ByteArray(0), output).status)
        assertTrue(output.contentEquals(seed))
    }
}
