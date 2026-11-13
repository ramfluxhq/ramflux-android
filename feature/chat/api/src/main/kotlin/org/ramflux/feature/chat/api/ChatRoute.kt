// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
@Serializable data class ChatRoute(val accountId: String, val conversationId: String) : NavKey
