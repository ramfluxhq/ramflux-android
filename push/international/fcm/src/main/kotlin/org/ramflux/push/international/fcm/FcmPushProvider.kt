// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.international.fcm

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ramflux.push.api.DeliveryChannel
import org.ramflux.push.api.ProviderState
import org.ramflux.push.api.PushProvider
import org.ramflux.push.api.PushToken
import org.ramflux.push.api.UnavailableReason

/** Placeholder until the Firebase client is linked: reports that this build has no push configuration. */
@Singleton
class FcmPushProvider @Inject constructor() : PushProvider {
    override val channel: DeliveryChannel = DeliveryChannel.FCM
    override val state: StateFlow<ProviderState> =
        MutableStateFlow<ProviderState>(ProviderState.Unavailable(UnavailableReason.NOT_CONFIGURED)).asStateFlow()

    override suspend fun token(): PushToken? = null

    override suspend fun rotate(): PushToken? = null

    override suspend fun delete() = Unit
}
