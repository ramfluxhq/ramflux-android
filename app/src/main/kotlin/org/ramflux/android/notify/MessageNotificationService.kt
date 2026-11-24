// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.ramflux.android.BuildConfig
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ConversationDirectoryRepository
import org.ramflux.data.NotificationChannelLabels
import org.ramflux.data.OsPermissionsRepository
import org.ramflux.feature.permissions.impl.R as PermissionsR

/**
 * Application-owned: creates the notification channels (with the permission flow's labels, through the one
 * channel implementation) and runs [MessageNotificationDriver] for the life of the process.
 */
@Singleton
class MessageNotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: ClientSessionRepository,
    private val accounts: AccountAccessRepository,
    private val conversations: ConversationDirectoryRepository,
    private val permissions: OsPermissionsRepository,
    private val routes: NotificationRoutes,
    private val banners: InAppBanners
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            permissions.createChannels(
                NotificationChannelLabels(
                    context.getString(PermissionsR.string.permissions_messages_channel),
                    context.getString(PermissionsR.string.permissions_calls_channel),
                    context.getString(PermissionsR.string.permissions_system_channel)
                )
            )
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        MessageNotificationDriver(
            session,
            accounts,
            conversations,
            AndroidMessageNotifier(context, secret = !BuildConfig.DEBUG),
            routes,
            banners
        ) { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }.start(scope)
    }
}
