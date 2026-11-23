// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidMessageNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun channel() {
        manager.createNotificationChannel(
            NotificationChannel(AndroidMessageNotifier.CHANNEL, "Messages", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @Test fun nothingIsPostedOrAskedWithoutThePermissionOrTheChannel() {
        val notifier = AndroidMessageNotifier(app, secret = true)
        channel()
        assertFalse("the permission is never requested by the notifier", notifier.allowed())
        notifier.post("token")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.deleteNotificationChannel(AndroidMessageNotifier.CHANNEL)
        assertFalse(notifier.allowed())
    }

    @Test fun theNotificationIsGenericAndHiddenOnTheLockScreenWhenCaptureIsBlocked() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        channel()
        AndroidMessageNotifier(app, secret = true).post("token-1")
        val secret = shadowOf(manager).allNotifications.single()
        assertEquals("New message", secret.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull("no message text", secret.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(Notification.VISIBILITY_SECRET, secret.visibility)
        val intent = shadowOf(secret.contentIntent).savedIntent
        assertEquals(setOf(AndroidMessageNotifier.EXTRA_TOKEN), intent.extras?.keySet())
        assertEquals("token-1", intent.getStringExtra(AndroidMessageNotifier.EXTRA_TOKEN))

        AndroidMessageNotifier(app, secret = false).post("token-2")
        val private = shadowOf(manager).allNotifications.first { it !== secret }
        assertEquals(Notification.VISIBILITY_PRIVATE, private.visibility)
        assertEquals("New message", private.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }
}
