// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives Firebase callbacks. Waking the app and registering a rotated token come with the wake path; until then a
 * message or a new token is ignored, and nothing is logged or stored.
 */
class RamfluxMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Unit

    override fun onMessageReceived(message: RemoteMessage) = Unit
}
