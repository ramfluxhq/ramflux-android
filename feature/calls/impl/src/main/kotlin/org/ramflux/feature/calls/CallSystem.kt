// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import android.app.Notification
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.ramflux.feature.calls.impl.R

/**
 * Self-managed system call integration: the phone shows Ramflux calls in its call surfaces and gives them
 * priority audio routing. Nothing here signals the call; the call view model stays the only caller of
 * `call.*`. It needs `MANAGE_OWN_CALLS` and the service declaration, neither of which is in the manifest
 * yet; [CallTelecom.declared] keeps every entry point inert until they are.
 */
class RamfluxConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        CallConnection().apply {
            setDialing()
        }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest?): Connection =
        CallConnection().apply {
            setRinging()
        }
}

/** One system call; it only mirrors what the call screen decided. */
class CallConnection : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
    }

    override fun onDisconnect() {
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onReject() {
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }
}

class CallTelecom(private val context: Context) {
    private val handle = PhoneAccountHandle(ComponentName(context, RamfluxConnectionService::class.java), ACCOUNT_ID)

    fun declared(): Boolean = try {
        context.packageManager.getServiceInfo(ComponentName(context, RamfluxConnectionService::class.java), 0)
        context.checkSelfPermission(MANAGE_OWN_CALLS) == PackageManager.PERMISSION_GRANTED
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Registers the self-managed account once the service is declared; a no-op before that. */
    fun register() {
        if (!declared()) return
        val account = PhoneAccount.builder(handle, context.getString(R.string.call_system_label))
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        context.getSystemService(TelecomManager::class.java).registerPhoneAccount(account)
    }

    fun placeOutgoing(conversation: String) {
        if (!declared()) return
        val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle) }
        context.getSystemService(TelecomManager::class.java).placeCall(
            Uri.fromParts(SCHEME, conversation.hashCode().toString(), null),
            extras
        )
    }

    fun reportIncoming() {
        if (!declared()) return
        context.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle, Bundle())
    }

    private companion object {
        const val ACCOUNT_ID = "ramflux"
        const val SCHEME = "ramflux"
        const val MANAGE_OWN_CALLS = android.Manifest.permission.MANAGE_OWN_CALLS
    }
}

/**
 * Keeps a live call in the foreground (types `phoneCall` and `microphone`) while an engine carries
 * audio. Not declared yet; with the refusing engine nothing starts it.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this), type)
        return START_NOT_STICKY
    }

    private companion object {
        const val NOTIFICATION_ID = 0x4341

        fun notification(context: Context): Notification = NotificationCompat.Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle(context.getString(R.string.call_ongoing))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }
}
