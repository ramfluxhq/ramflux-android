// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.ramflux.navigation.AccountScoped

/** One call: an outgoing call to [conversationId], or the existing call [callId] (incoming or live). */
@Serializable data class CallRoute(val accountId: String, val conversationId: String? = null, val callId: String? = null) : NavKey, AccountScoped
