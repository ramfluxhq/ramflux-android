// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.ramflux.android.MainActivity
import org.ramflux.android.R

/**
 * The platform side of [MessageNotifier]. The notification says only "New message" (from resources): no sender,
 * title, text or conversation id; the tap carries a random token that [NotificationRoutes] maps back in memory.
 * With [secret] (release windows reject capture, `FLAG_SECURE`) it is hidden on the lock screen
 * (`VISIBILITY_SECRET`); otherwise it is private with a public version that says the same generic words.
 */
class AndroidMessageNotifier(private val context: Context, private val secret: Boolean) : MessageNotifier {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    override fun allowed(): Boolean {
        val notifications = manager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val channel = notifications.getNotificationChannel(CHANNEL) ?: return false
        return notifications.areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun post(token: String) {
        if (!allowed()) return
        val title = context.getString(R.string.notification_new_message)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_TOKEN, token)
        val tap = PendingIntent.getActivity(
            context,
            token.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(if (secret) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE)
            .apply { if (!secret) setPublicVersion(public) }
            .build()
        try {
            manager?.notify(token.hashCode(), notification)
        } catch (_: SecurityException) {
            // The permission was withdrawn between the check and the post: nothing is shown, nothing is asked.
        }
    }

    companion object {
        /** The message channel created with the permission flow's labels (`AndroidOsPermissionsRepository`). */
        const val CHANNEL = "messages"
        const val EXTRA_TOKEN = "org.ramflux.notification.token"
    }
}
