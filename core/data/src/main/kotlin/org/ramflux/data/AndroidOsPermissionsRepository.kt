// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
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
        require(listOf(labels.messages, labels.calls, labels.system, labels.missedCalls).all { it.isNotBlank() && it.length <= 256 })
        // Importance is fixed when a channel is first created; an existing channel keeps what the user or an earlier build set.
        app.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel("messages", labels.messages, NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                // The incoming-call ring: ringtone sound and vibration, shown on the lock screen as a generic call.
                NotificationChannel("calls", labels.calls, NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                },
                NotificationChannel("missed_calls", labels.missedCalls, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel("system", labels.system, NotificationManager.IMPORTANCE_LOW).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setSound(null, null)
                }
            )
        )
    }
    override suspend fun claimPrompt(permission: OsPermission) = local {
        require(permission in PROMPTABLE)
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

/** Runtime permissions the app asks for, each only after its explanation. */
private val PROMPTABLE = setOf(OsPermission.CAMERA, OsPermission.MICROPHONE, OsPermission.NOTIFICATIONS)
