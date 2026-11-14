// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import org.ramflux.data.OsPermission

object PermissionsPlatform {
    fun appSettings(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    fun batterySettings() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    fun permissionName(permission: OsPermission): String? = when (permission) {
        OsPermission.CAMERA -> Manifest.permission.CAMERA
        OsPermission.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        else -> null // Media permissions cannot be requested in v1.
    }
    fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current as Activity
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
    fun validSelection(uri: Uri?) = uri?.scheme == "content"
    fun photoPicker() = ActivityResultContracts.PickVisualMedia()
    fun filePicker() = ActivityResultContracts.OpenDocument()
    fun exportFile() = ActivityResultContracts.CreateDocument("application/octet-stream")
}
