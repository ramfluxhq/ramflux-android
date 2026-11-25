// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.api

import kotlinx.coroutines.flow.StateFlow

/** How a wake reaches this phone. [VENDOR] is reserved for a later edition. */
enum class DeliveryChannel { FCM, PERSISTENT_CONNECTION, VENDOR }

sealed interface ProviderState {
    data object Ready : ProviderState

    data class Unavailable(val reason: UnavailableReason) : ProviderState
}

enum class UnavailableReason {
    NOT_CONFIGURED,
    PLAY_SERVICES_MISSING,
    PLAY_SERVICES_UPDATE_REQUIRED,
    PLAY_SERVICES_DISABLED,
    PLAY_SERVICES_INVALID,
    TOKEN_FAILED,
    NODE_REFUSED,
    CENTRE_UNAVAILABLE
}

/** A provider token. It lives in memory only and is never logged, so [toString] hides the value. */
class PushToken(val value: String) {
    override fun toString(): String = "PushToken(<redacted>)"
}

interface PushProvider {
    val channel: DeliveryChannel
    val state: StateFlow<ProviderState>

    /** The current token, or null when none can be had. Never persisted or logged. */
    suspend fun token(): PushToken?

    /** Deletes the current token, then obtains a new one. */
    suspend fun rotate(): PushToken?

    suspend fun delete()
}
