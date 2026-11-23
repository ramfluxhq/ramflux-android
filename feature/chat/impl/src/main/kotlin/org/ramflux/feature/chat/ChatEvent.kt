// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
sealed interface ChatEvent {
    data object Back : ChatEvent
    data object Sent : ChatEvent
    data object Details : ChatEvent
    data object Call : ChatEvent
    data object Storage : ChatEvent

    data class Pick(val source: PickSource) : ChatEvent

    /** A decrypted app-private file to hand to the system (view after confirmation, share or save). */
    data class File(val path: String, val mime: String, val name: String, val use: FileUse) : ChatEvent {
        override fun toString() = "File(<redacted>)"
    }

    /** Copy [text] to the clipboard; it is message plaintext and is never logged. */
    data class Copy(val text: String) : ChatEvent {
        override fun toString() = "Copy(<redacted>)"
    }
}

enum class FileUse { OPEN, SHARE, SAVE }

/** Where new media comes from: the system photo picker, the document picker or the system camera. */
enum class PickSource { PHOTO, FILE, CAMERA }
