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

    /** Opens the system photo picker, the document picker or the system camera. */
    data class Pick(val source: PickSource) : ChatAction

    /** Picker results; [uris] are the opaque content URIs the picker or the camera returned. */
    data class Picked(val uris: List<String>, val source: PickSource) : ChatAction

    /** Opens the forward chooser for message [messageId] (the selected one when null), or one item of it. */
    data class Forward(val messageId: String? = null, val index: Int? = null) : ChatAction

    /** The forward chooser reached its last loaded conversation. */
    data object ForwardMore : ChatAction

    /** Sends the message being forwarded to [conversation] again, with new keys. */
    data class ForwardTo(val conversation: String) : ChatAction
    data object CloseForward : ChatAction

    /** Removes item [index] from the send preview. */
    data class PreviewRemove(val index: Int) : ChatAction

    /** Moves item [index] of the send preview one place towards the start. */
    data class PreviewMove(val index: Int) : ChatAction
    data class PreviewCaption(val text: String) : ChatAction
    data object PreviewSend : ChatAction
    data object PreviewCancel : ChatAction

    /** Shows the next ([delta] 1) or previous (-1) picture or video of the message in the viewer. */
    data class ViewerStep(val delta: Int) : ChatAction

    /** The screen left the foreground; the draft is sealed to storage now. */
    data object Pause : ChatAction
}
