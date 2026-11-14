// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidOsPermissionsRepository(context: Context) : OsPermissionsRepository {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("os-permissions", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private suspend fun <T> local(block: () -> T): OsPermissionResult<T> = withContext(Dispatchers.IO) {
        lock.withLock {
            try { OsPermissionResult.Ready(block()) } catch (_: RuntimeException) { OsPermissionResult.Failed }
        }
    }
    private fun runtime(permission: OsPermission, name: String): OsPermissionStatus {
        if (app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) {
            check(preferences.edit().putBoolean("observed-${permission.name}", true).commit())
            return OsPermissionStatus.ALLOWED
        }
        return if (preferences.getBoolean("asked-${permission.name}", false) ||
            preferences.getBoolean("observed-${permission.name}", false)) OsPermissionStatus.DENIED else OsPermissionStatus.NOT_ASKED
    }
    override suspend fun read() = local {
        val notifications = app.getSystemService(NotificationManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= 33) runtime(OsPermission.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS)
            else if (notifications.areNotificationsEnabled()) OsPermissionStatus.ALLOWED else OsPermissionStatus.DENIED
        OsPermission.entries.map { permission ->
            OsPermissionRow(permission, when (permission) {
                OsPermission.NOTIFICATIONS -> if (status == OsPermissionStatus.ALLOWED && !notifications.areNotificationsEnabled())
                    OsPermissionStatus.DENIED else status
                OsPermission.MICROPHONE -> runtime(permission, Manifest.permission.RECORD_AUDIO)
                OsPermission.CAMERA -> runtime(permission, Manifest.permission.CAMERA)
                OsPermission.INCOMING_CALL -> if (Build.VERSION.SDK_INT >= 34) {
                    if (notifications.canUseFullScreenIntent()) OsPermissionStatus.ALLOWED else OsPermissionStatus.DENIED
                } else OsPermissionStatus.UNAVAILABLE
                OsPermission.BATTERY -> if (app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName))
                    OsPermissionStatus.ALLOWED else OsPermissionStatus.DENIED
                OsPermission.SAVE_PHOTO, OsPermission.PHOTOS_FILES -> OsPermissionStatus.NOT_REQUIRED
            })
        }
    }
    override suspend fun createChannels(labels: NotificationChannelLabels) = local {
        require(listOf(labels.messages, labels.calls, labels.system).all { it.isNotBlank() && it.length <= 256 })
        app.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf("messages" to labels.messages, "calls" to labels.calls, "system" to labels.system).map { (id, label) ->
                NotificationChannel(id, label, NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE }
            }
        )
    }
    override suspend fun claimPrompt(permission: OsPermission) = local {
        require(permission == OsPermission.CAMERA || permission == OsPermission.NOTIFICATIONS)
        val key = "asked-${permission.name}"
        if (preferences.getBoolean(key, false)) false else { check(preferences.edit().putBoolean(key, true).commit()); true }
    }
    override suspend fun claimCreatedAccountExplanation(accountId: String) = local {
        require(accountId.isNotBlank() && accountId.length <= 256)
        // Store a bounded digest, never account identifiers or permission state in the SDK.
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val key = "created-$digest"
        if (preferences.getBoolean(key, false)) false else {
            check(preferences.all.keys.count { it.startsWith("created-") } < 256)
            check(preferences.edit().putBoolean(key, true).commit()); true
        }
    }
}
