// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = RamfluxApplication::class, sdk = [28, 35])
class PermissionsManifestTest {
    @Test fun packagedManifestRequestsOnlyTheApprovedPermissions() {
        val app = ApplicationProvider.getApplicationContext<RamfluxApplication>()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val privateReceiver = "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        assertEquals(
            setOf(
                Manifest.permission.INTERNET,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                // Brought by the allowlisted media transfer service (scripts/os-service-allowlist.json).
                Manifest.permission.FOREGROUND_SERVICE,
                FOREGROUND_SERVICE_DATA_SYNC
            ),
            requireNotNull(info.requestedPermissions).toSet() - privateReceiver
        )
    }

    @Test fun theMediaTransferServiceIsDeclaredAndNotExported() {
        val app = ApplicationProvider.getApplicationContext<RamfluxApplication>()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SERVICES)
        val services = requireNotNull(info.services).associateBy { it.name }
        assertEquals(setOf("org.ramflux.feature.chat.MediaTransferService"), services.keys)
        assertEquals(false, services.getValue("org.ramflux.feature.chat.MediaTransferService").exported)
    }

    private companion object {
        /** Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC is API 34; the manifest names it on every level. */
        const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    }
}

