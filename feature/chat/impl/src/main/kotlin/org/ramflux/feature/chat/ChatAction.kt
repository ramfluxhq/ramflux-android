// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
sealed interface ChatAction {
    data class Load(val accountId: String, val conversationId: String, val groupId: String? = null) : ChatAction
    data object Refresh : ChatAction
    data class Draft(val text: String) : ChatAction
    data object Send : ChatAction
    data object Back : ChatAction
    data class Select(val id: String) : ChatAction
    data object Dismiss : ChatAction
    data object Reply : ChatAction
    data object Edit : ChatAction

    /** Adds [token], one grapheme the SDK accepts, to the selected message. */
    data class React(val token: String) : ChatAction
    data object Copy : ChatAction
    data object DeleteForMe : ChatAction
    data object DeleteForEveryone : ChatAction
    data object CancelComposer : ChatAction

    /** The timeline reached its oldest loaded row. */
    data object LoadOlder : ChatAction

    /** [id] is the newest row currently on screen. */
    data class Shown(val id: String) : ChatAction

    /** Sends a failed or queued local message again. */
    data class Retry(val id: String) : ChatAction

    /** Drops a failed local message. */
    data class Discard(val id: String) : ChatAction
    data object Details : ChatAction
    data object Call : ChatAction

    /** Downloads, opens or views the attachment with [key] depending on its state. */
    data class OpenAttachment(val key: String) : ChatAction
    data object CloseViewer : ChatAction
    data class SaveAttachment(val key: String) : ChatAction
    data class ShareAttachment(val key: String) : ChatAction

    /** Opens the system photo picker ([image]) or the document picker. */
    data class Pick(val image: Boolean) : ChatAction

    /** A picker result; [uri] is the opaque content URI the picker returned. */
    data class Picked(val uri: String, val image: Boolean) : ChatAction

    /** The screen left the foreground; the draft is sealed to storage now. */
    data object Pause : ChatAction
}
