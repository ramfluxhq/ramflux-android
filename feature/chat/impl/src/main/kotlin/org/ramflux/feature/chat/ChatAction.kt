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
    data class Select(val id: String) : ChatAction
    data object Dismiss : ChatAction
    data object Reply : ChatAction
    data object Edit : ChatAction
    data object React : ChatAction
    data object DeleteForMe : ChatAction
    data object DeleteForEveryone : ChatAction
    data object CancelComposer : ChatAction
}
