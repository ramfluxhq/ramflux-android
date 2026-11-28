// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import kotlinx.serialization.json.JsonObject
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardSlotOutcome
import org.ramflux.data.cards.CardStamp
sealed interface ChatAction {
    /** A host event for the control at [key] of agent card [ref] (`input`, `activate`, `select_tab`, modal events). */
    data class CardInteract(val ref: String, val key: String, val event: CardEvent, val seen: CardStamp) : ChatAction

    /** The answer to a card's link or approval slot. [url] is the link the sheet showed; null for approvals. */
    data class CardAnswer(
        val ref: String,
        val slotId: String,
        val requestId: String,
        val token: JsonObject,
        val outcome: CardSlotOutcome,
        val url: String?
    ) : ChatAction {
        override fun toString() = "CardAnswer(<redacted>)"
    }

    /** First tap on a suspended card: resume it. */
    data class CardShow(val ref: String) : ChatAction

    /** Out of sync: re-read the card from the latest surface. */
    data class CardResync(val ref: String) : ChatAction

    /** The app locale and theme the cards draw with; changes are sent to the card runtime. */
    data class CardAppearance(val locale: String, val dark: Boolean) : ChatAction

    data class Load(val accountId: String, val conversationId: String, val groupId: String? = null, val title: String? = null) : ChatAction
    data object Refresh : ChatAction

    /** An MLS group window that lost its newest rows loads the newest page again. */
    data object JumpToNewest : ChatAction
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

    data object OpenGallery : ChatAction

    /** The gallery reached its last loaded item. */
    data object GalleryMore : ChatAction
    data class GalleryTab(val tab: org.ramflux.feature.chat.GalleryTab) : ChatAction
    data object CloseGallery : ChatAction

    /** Opens the media storage screen of this account. */
    data object Storage : ChatAction

    /** Shows the next ([delta] 1) or previous (-1) picture or video of the message in the viewer. */
    data class ViewerStep(val delta: Int) : ChatAction

    /** "Review" on the identity banner or the composer prompt: opens the identity change sheet. */
    data object ReviewIdentity : ChatAction

    /** The explicit "Accept new identity" tap of the sheet; the only way a changed identity is accepted. */
    data object AcceptIdentity : ChatAction

    /** "Not now" in the sheet. */
    data object LaterIdentity : ChatAction

    /** The screen left the foreground; the draft is sealed to storage now. */
    data object Pause : ChatAction

    /** MLS group chat: the confirmed "Delete for everyone" of the selected row, and its dismissal. */
    data object ConfirmDelete : ChatAction
    data object CancelDelete : ChatAction

    /**
     * MLS group chat (M3). Skip opens the dialog; only [MlsConfirmSkip] calls `group.catch_up{skip: true}`. [MlsHideBanner] hides
     * a hideable banner for the session. [MlsShowUndecryptable] scrolls to the placeholder row (older pages load by the M2
     * paging path). [MlsUndo] opens the void confirmation for the row (or the log position `head_commit_rejected` names).
     */
    data object MlsSkip : ChatAction
    data object MlsConfirmSkip : ChatAction
    data object MlsCancelSkip : ChatAction
    data class MlsHideBanner(val code: String) : ChatAction
    data object MlsShowUndecryptable : ChatAction
    data class MlsUndo(val rowId: String) : ChatAction
    data object MlsConfirmUndo : ChatAction
    data object MlsCancelUndo : ChatAction
    data object MlsOpenInvite : ChatAction
    data object MlsOpenAlerts : ChatAction

    /** MLS group chat: the mention picker over the loaded members. */
    data object MentionOpen : ChatAction
    data object MentionMore : ChatAction
    data object MentionClose : ChatAction
    data class MentionPick(val anchor: String) : ChatAction {
        override fun toString() = "MentionPick(<redacted>)"
    }
}
