// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.selftest

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.impl.R
import org.ramflux.push.signals.AndroidDeliverySignals

/** Posts the local test notifications of the self-test (design 5.4). They carry no content and touch no account or network. */
@Singleton
class LocalSelfTest @Inject constructor(@ApplicationContext private val context: Context) {
    /** A generic message notification on the messages channel. Returns false when nothing could be posted. */
    fun postMessage(): Boolean {
        val builder = builder(AndroidDeliverySignals.CHANNEL_MESSAGES) ?: return false
        val public = builder(AndroidDeliverySignals.CHANNEL_MESSAGES)
            ?.setContentTitle(context.getString(R.string.delivery_selftest_message_title))
            ?.build()
        val notification = builder
            .setContentTitle(context.getString(R.string.delivery_selftest_message_title))
            .setContentText(context.getString(R.string.delivery_selftest_text))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .apply { public?.let { setPublicVersion(it) } }
            .build()
        return notify(MESSAGE_ID, notification)
    }

    /** A ringing call notification on the calls channel; it takes over a locked screen where the system allows it. */
    fun postCall(): Boolean {
        val builder = builder(AndroidDeliverySignals.CHANNEL_CALLS) ?: return false
        val launch = launchIntent()
        val notification = builder
            .setContentTitle(context.getString(R.string.delivery_selftest_call_title))
            .setContentText(context.getString(R.string.delivery_selftest_text))
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .apply { launch?.let { setFullScreenIntent(it, true) } }
            .build()
        notification.flags = notification.flags or Notification.FLAG_INSISTENT
        return notify(CALL_ID, notification)
    }

    private fun builder(channel: String): Notification.Builder? {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel) == null) return null
        return Notification.Builder(context, channel)
            .setSmallIcon(Icon.createWithResource(context, context.applicationInfo.icon))
            .apply { launchIntent()?.let { setContentIntent(it) } }
    }

    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notify(id: Int, notification: Notification): Boolean = try {
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
        true
    } catch (_: SecurityException) {
        // The permission was withdrawn between the check and the post.
        false
    }

    private companion object {
        const val MESSAGE_ID = 0x5253
        const val CALL_ID = 0x5254
        const val RING_TIMEOUT_MS = 15_000L
    }
}
