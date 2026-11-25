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
                Manifest.permission.USE_FULL_SCREEN_INTENT,
                // Brought by the allowlisted media transfer service (scripts/os-service-allowlist.json).
                Manifest.permission.FOREGROUND_SERVICE,
                FOREGROUND_SERVICE_DATA_SYNC,
                // Brought by the allowlisted messaging service of the Firebase module.
                "com.google.android.c2dm.permission.RECEIVE"
            ),
            requireNotNull(info.requestedPermissions).toSet() - privateReceiver
        )
    }

    @Test fun theAppsOwnServicesAreDeclaredAndNotExported() {
        val app = ApplicationProvider.getApplicationContext<RamfluxApplication>()
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SERVICES)
        // Libraries add services of their own (Firebase); the app's own are the allowlisted ones.
        val services = requireNotNull(info.services).filter { it.name.startsWith("org.ramflux.") }.associateBy { it.name }
        assertEquals(
            setOf("org.ramflux.feature.chat.MediaTransferService", "org.ramflux.push.international.fcm.RamfluxMessagingService"),
            services.keys
        )
        assertEquals(false, services.getValue("org.ramflux.feature.chat.MediaTransferService").exported)
        assertEquals(false, services.getValue("org.ramflux.push.international.fcm.RamfluxMessagingService").exported)
    }

    private companion object {
        /** Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC is API 34; the manifest names it on every level. */
        const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    }
}
