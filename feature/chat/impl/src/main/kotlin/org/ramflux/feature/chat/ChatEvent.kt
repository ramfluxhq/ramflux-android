// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
sealed interface ChatEvent {
    data object Attachments : ChatEvent
    data object Back : ChatEvent
    data object Sent : ChatEvent
    data object Details : ChatEvent
    data object Call : ChatEvent

    /** Copy [text] to the clipboard; it is message plaintext and is never logged. */
    data class Copy(val text: String) : ChatEvent {
        override fun toString() = "Copy(<redacted>)"
    }
}
