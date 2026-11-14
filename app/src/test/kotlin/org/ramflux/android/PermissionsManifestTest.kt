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
                Manifest.permission.RECORD_AUDIO
            ),
            requireNotNull(info.requestedPermissions).toSet() - privateReceiver
        )
    }
}
