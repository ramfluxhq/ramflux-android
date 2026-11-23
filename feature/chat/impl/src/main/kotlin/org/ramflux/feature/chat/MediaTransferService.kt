// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ramflux.data.MediaTransfers
import org.ramflux.feature.chat.impl.R

/**
 * Background transfer up to the service boundary (media design 5.10, M3). Imports and uploads run over
 * the SDK's live gateway session in this process; WorkManager cannot run them after the session
 * closes, because nothing can open the account without the person unlocking it. What this service
 * honestly offers is to keep the process in the foreground, with a visible notification, while
 * transfers the person started are still running inside the unlocked session. It never opens the SDK,
 * holds no secrets and stops as soon as the queue is idle or the session closes.
 *
 * It is not declared in the manifest yet; [MediaTransferServiceController] detects that and does
 * nothing until the service and its `dataSync` permission are declared.
 */
class MediaTransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        // Restarting after process death would find no unlocked session and nothing to transfer.
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "org.ramflux.media.transfer.stop"
        private const val NOTIFICATION_ID = 0x4d54

        /** The existing "system" channel; the notification names no file, sender or conversation. */
        private const val CHANNEL = "system"

        internal fun notification(context: Context): Notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.transfer_notification_title))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }
}

/**
 * Starts [MediaTransferService] while [MediaTransfers.active] is true and stops it when the queue is
 * idle. Until the manifest declares the service this is a no-op, so enabling background transfer is a
 * manifest change only.
 */
class MediaTransferServiceController(private val context: Context, private val transfers: MediaTransfers) {
    fun start(scope: CoroutineScope) {
        if (!declared()) return
        scope.launch {
            transfers.active.collect { running ->
                val intent = Intent(context, MediaTransferService::class.java)
                if (running) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent.setAction(MediaTransferService.ACTION_STOP))
                }
            }
        }
    }

    fun declared(): Boolean = try {
        context.packageManager.getServiceInfo(ComponentName(context, MediaTransferService::class.java), PackageManager.GET_META_DATA)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
