// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
sealed interface ChatEvent {
    data object Attachments : ChatEvent
    data object Back : ChatEvent
    data object Sent : ChatEvent
}
