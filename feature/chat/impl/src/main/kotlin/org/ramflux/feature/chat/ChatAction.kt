// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
sealed interface ChatAction {
    data class Load(val accountId: String, val conversationId: String, val groupId: String? = null) : ChatAction
    data object Refresh : ChatAction
    data class Draft(val text: String) : ChatAction
    data object Send : ChatAction
    data object Attachments : ChatAction
    data object Back : ChatAction
}
