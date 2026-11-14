// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class OsPermissionsPlatformTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun List<OsPermissionRow>.status(permission: OsPermission) = first { it.permission == permission }.status

    @Test fun realPermissionGrantRevocationAndPromptClaimsSurviveRepositoryRecreation() = runTest(timeout = 5.seconds) {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        val repo = AndroidOsPermissionsRepository(app)
        assertEquals(OsPermissionStatus.NOT_ASKED, (repo.read() as OsPermissionResult.Ready).value.status(OsPermission.CAMERA))
        assertEquals(true, (repo.claimPrompt(OsPermission.CAMERA) as OsPermissionResult.Ready).value)
        assertEquals(false, (AndroidOsPermissionsRepository(app).claimPrompt(OsPermission.CAMERA) as OsPermissionResult.Ready).value)
        assertEquals(OsPermissionStatus.DENIED, (repo.read() as OsPermissionResult.Ready).value.status(OsPermission.CAMERA))
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        assertEquals(OsPermissionStatus.ALLOWED, (repo.read() as OsPermissionResult.Ready).value.status(OsPermission.CAMERA))
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        assertEquals(OsPermissionStatus.DENIED, (repo.read() as OsPermissionResult.Ready).value.status(OsPermission.CAMERA))
        assertTrue(repo.claimPrompt(OsPermission.MICROPHONE) is OsPermissionResult.Failed)
    }

    @Test fun postCreationExplanationIsDurablePerAccountAndChannelsUpdateNamesWithoutRequestingPermission() = runTest(timeout = 5.seconds) {
        app.getSharedPreferences("os-permissions", 0).edit().clear().commit()
        val repo = AndroidOsPermissionsRepository(app)
        assertEquals(true, (repo.claimCreatedAccountExplanation("account") as OsPermissionResult.Ready).value)
        assertEquals(
            false,
            (AndroidOsPermissionsRepository(app).claimCreatedAccountExplanation("account") as OsPermissionResult.Ready).value
        )
        assertEquals(true, (repo.claimCreatedAccountExplanation("other") as OsPermissionResult.Ready).value)
        assertTrue(repo.claimCreatedAccountExplanation("") is OsPermissionResult.Failed)
        assertTrue(repo.createChannels(NotificationChannelLabels("Messages", "Calls", "System")) is OsPermissionResult.Ready)
        assertTrue(repo.createChannels(NotificationChannelLabels("消息", "来电", "系统")) is OsPermissionResult.Ready)
        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals(setOf("messages", "calls", "system"), manager.notificationChannels.map { it.id }.toSet())
        assertEquals("消息", manager.getNotificationChannel("messages").name.toString())
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun realPickerExporterAndSettingsContractsUseMinimumAccess() {
        val photo = PermissionsPlatform.photoPicker().createIntent(
            app,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
        assertTrue(photo.action in setOf(Intent.ACTION_OPEN_DOCUMENT, "android.provider.action.PICK_IMAGES"))
        val file = PermissionsPlatform.filePicker().createIntent(app, arrayOf("*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, file.action)
        val export = PermissionsPlatform.exportFile().createIntent(app, "backup.rf")
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, export.action)
        val settings = PermissionsPlatform.appSettings(app)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${app.packageName}", settings.data.toString())
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, PermissionsPlatform.batterySettings().action)
        assertNull(PermissionsPlatform.permissionName(OsPermission.MICROPHONE))
        assertNull(PermissionsPlatform.permissionName(OsPermission.INCOMING_CALL))
        assertEquals(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            PermissionsPlatform.permissionName(OsPermission.NOTIFICATIONS)
        )
        assertTrue(PermissionsPlatform.validSelection(Uri.parse("content://picker/photo")))
        assertFalse(PermissionsPlatform.validSelection(Uri.parse("file:///private/path")))
        assertFalse(PermissionsPlatform.validSelection(Uri.parse("https://peer.example/photo")))
    }
}
