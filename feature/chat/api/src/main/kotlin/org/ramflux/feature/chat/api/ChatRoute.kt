// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat.api
import androidx.navigation3.runtime.NavKey
import org.ramflux.navigation.AccountScoped
import kotlinx.serialization.Serializable
/** One conversation. [title] is the list row's title, shown until the chat reads its own header. */
@Serializable data class ChatRoute(
    val accountId: String,
    val conversationId: String,
    val groupId: String? = null,
    val title: String? = null
) : NavKey, AccountScoped
