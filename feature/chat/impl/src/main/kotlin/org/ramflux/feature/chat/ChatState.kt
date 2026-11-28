// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.IdentityReviewState
import org.ramflux.data.IdentityState
import org.ramflux.data.cards.CardImageSource
import org.ramflux.data.cards.CardState
import org.ramflux.designsystem.UiText
/** Timeline entries carry typed values; the screen formats times and dates for the current locale. */
@Immutable sealed interface TimelineItem {
    val id: String

    /** Creation time in epoch seconds, or null when the read model has none. */
    val sentAt: Long?
}

@Immutable data class CallItem(override val id: String, override val sentAt: Long?) : TimelineItem

/**
 * An agent surface in the timeline (beta flow 13). [state] is the card runtime's view of the card; the renderer draws its
 * tree, lifecycle and host requests. [staleKey] and [staleSeq] report the last control whose event came back stale.
 */
@Immutable data class CardItem(
    override val id: String,
    override val sentAt: Long?,
    val ref: String,
    val state: CardState,
    val staleKey: String? = null,
    val staleSeq: Long = 0,
    /** Where this account's card images are read (the object API); images are unavailable without it. */
    val images: CardImageSource = CardImageSource.None
) : TimelineItem {
    override fun toString() = "CardItem(<redacted>)"
}

/** The strongest delivery state the read model reports for an own message. */
enum class Receipt { SENT, DELIVERED, READ }

enum class ComposerMode { SEND, REPLY, EDIT }

/** A message this device has not yet handed to the SDK, shown at the end of the timeline. */
enum class LocalDelivery {
    /** Waiting for a live session; it is sent when the connection returns (board MOB-B08). */
    QUEUED,
    SENDING,

    /** Refused or failed; the person can retry or discard it (board MOB-B05). */
    FAILED
}

/** One reaction token with its count; the token is peer-controlled text. */
@Immutable data class ReactionCount(val token: String, val count: Int)

/** What an MLS group row shows besides its text: a send state (the SDK owns queued and delayed posts) or "Deleting…". */
enum class GroupDeliveryKind { QUEUED, DELAYED, NOT_SENT, DELETING }

/** The typed reason a group state line shows; the screen turns it into its text. */
enum class GroupReason {
    CATCHING_UP,
    HOST_UNREACHABLE,
    FETCHING_TOKENS,
    WAITING,
    DELAYED,
    RATE_LIMITED,
    RATE_LIMITED_NO_RESET,
    FIRST_DAY,
    TOO_LARGE,
    NOT_MEMBER,
    REJOIN_NEEDED,
    PACED,
    DELETING,
    UNAVAILABLE,
    MESSAGE_NOT_FOUND,
    NOT_AUTHOR,
    GENERIC
}

/** [number] is the time (unix ms), the byte limit or the count the reason's text names. */
@Immutable data class GroupDelivery(val kind: GroupDeliveryKind, val reason: GroupReason, val number: Long? = null)

/** A group service row (member change, settings, a row the client cannot draw). Names are peer data. */
@Immutable data class GroupName(val display: String?, val me: Boolean) {
    override fun toString() = "GroupName(<redacted>)"
}

@Immutable sealed interface GroupLine {
    data class Joined(val first: GroupName, val others: Int) : GroupLine
    data class Left(val first: GroupName, val others: Int) : GroupLine
    data class Removed(val actor: GroupName?, val target: GroupName) : GroupLine
    data class Banned(val actor: GroupName?, val target: GroupName) : GroupLine
    data class Role(val actor: GroupName?, val target: GroupName, val admin: Boolean) : GroupLine
    data class Renamed(val actor: GroupName?, val name: String) : GroupLine
    data class Approval(val actor: GroupName?, val on: Boolean) : GroupLine
    data object Void : GroupLine
    data object Updated : GroupLine
    data object Unsupported : GroupLine
    data object Floor : GroupLine
}

@Immutable data class MessageItem(
    override val id: String,
    val sender: String,
    val own: Boolean,
    val deleted: Boolean,
    val content: UiText,
    override val sentAt: Long? = null,
    val revision: Long = 0,
    val edited: Boolean = false,
    val replyPreview: UiText? = null,
    val placeholder: Boolean = false,
    val service: Boolean = false,
    val receipt: Receipt? = null,
    val reactions: ImmutableList<ReactionCount> = persistentListOf(),
    val delivery: LocalDelivery? = null,
    val attachments: ImmutableList<AttachmentItem> = persistentListOf(),
    /** The author is an SDK-verified AI agent (shown with the AI marker). */
    val senderAi: Boolean = false,
    /** The MLS group send or delete state of this row; null for every direct-chat row. */
    val groupDelivery: GroupDelivery? = null,
    /** The MLS group service row to draw instead of [content]; null for every direct-chat row. */
    val line: GroupLine? = null,
    /** An own, posted MLS group row: "Delete for everyone" is offered. */
    val deletable: Boolean = false
) : TimelineItem {
    override fun toString() = "MessageItem(<redacted>)"
}

@Immutable data class ChatState(
    val conversationId: String = "",
    val groupId: String? = null,
    val title: UiText? = null,
    /** The direct peer is an SDK-verified AI agent (the header shows the AI marker). */
    val titleAi: Boolean = false,
    val headerProblem: UiText? = null,
    val rows: ImmutableList<TimelineItem> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sending: Boolean = false,
    val draft: String = "",
    val canSend: Boolean = false,
    val problem: UiText? = null,
    val unavailable: UiText? = null,
    val composer: ComposerMode = ComposerMode.SEND,
    val actionTarget: String? = null,
    val expectedRevision: Long = 0,
    val selectedId: String? = null,
    val peerTyping: Boolean = false,
    val deliveryNotice: UiText? = null,
    val typingProblem: UiText? = null,
    /** The first unread message, when the read model reports one; the timeline draws the unread divider above it. */
    val firstUnreadId: String? = null,
    /** The SDK reported an older page; scrolling to the top loads it. */
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** At least one send waits for a live session; the timeline shows the offline notice. */
    val queued: Boolean = false,
    /** The image shown full screen, when one is open. */
    val viewer: AttachmentItem? = null,
    /** The forward chooser, while it is open. */
    val forward: ForwardSheet? = null,
    /** The send preview of picked photos and videos, while it is open. */
    val preview: SendPreview? = null,
    /** "2 of 5" in the viewer when the message has more than one picture or video. */
    val viewerPosition: ViewerPosition? = null,
    /** The conversation gallery, while it is open. */
    val gallery: GallerySheet? = null,
    /** The direct peer's name for the identity banner and sheet (peer-controlled); null until the header names it. */
    val peerName: String? = null,
    /** The direct peer's `identity_state`; [IdentityState.CHANGED] shows the banner, which has no dismiss. */
    val identityState: IdentityState = IdentityState.UNKNOWN,
    /** A send was refused with `identity_change_pending`: the composer shows the prompt while the identity stays changed. */
    val sendBlocked: Boolean = false,
    /** The identity change sheet, while it is open. */
    val review: IdentityReviewState? = null,
    /** The MLS group chat extras; null for every other chat. */
    val mls: MlsChatUi? = null
) {
    override fun toString() = "ChatState(<redacted>)"
}

/** One member the mention picker offers. [display] is null for a non-contact (the screen shows "Member"). */
@Immutable data class MentionRow(val anchor: String, val display: String?, val ai: Boolean) {
    override fun toString() = "MentionRow(<redacted>)"
}

@Immutable data class MentionSheet(
    val rows: ImmutableList<MentionRow> = persistentListOf(),
    val loading: Boolean = false,
    val hasMore: Boolean = false
) {
    override fun toString() = "MentionSheet(<redacted>)"
}

/** Why an MLS group is read-only: no composer is shown and nothing is sent (S13). */
enum class GroupReadOnly { LEFT, REMOVED, BANNED }

/** The extras of an MLS group chat; null for a direct chat and for the old group chat. */
@Immutable data class MlsChatUi(
    val readOnly: GroupReadOnly? = null,
    /** The composer is off until this unix-ms time (a daily limit). */
    val limitedUntil: Long? = null,
    val refreshing: Boolean = false,
    val confirmDelete: Boolean = false,
    val mention: MentionSheet? = null,
    val notice: GroupDelivery? = null
) {
    override fun toString() = "MlsChatUi(<redacted>)"
}

/** One conversation the chooser offers; [title] is peer-controlled. */
@Immutable data class ForwardRow(val conversation: String, val title: String, val group: String? = null) {
    override fun toString() = "ForwardRow(<redacted>)"
}

/** The forward chooser: a paged list of conversations, following the directory cursor. */
@Immutable data class ForwardSheet(
    val rows: ImmutableList<ForwardRow> = persistentListOf(),
    val loading: Boolean = true,
    val hasMore: Boolean = false,
    val problem: UiText? = null,
    /** The message is being re-sent; the chooser shows progress without a percentage. */
    val sending: Boolean = false
) {
    override fun toString() = "ForwardSheet(<redacted>)"
}

enum class GalleryTab { MEDIA, FILES }

/** The conversation gallery: one tab of items from a bounded window over the paged history. */
@Immutable data class GallerySheet(
    val tab: GalleryTab = GalleryTab.MEDIA,
    val items: ImmutableList<AttachmentItem> = persistentListOf(),
    val loading: Boolean = true,
    val hasMore: Boolean = false,
    val problem: UiText? = null
) {
    override fun toString() = "GallerySheet(<redacted>)"
}

@Immutable data class ViewerPosition(val index: Int, val count: Int)

/** One staged item of the send preview; [path] is its outbox file. */
@Immutable data class PreviewItem(val index: Int, val kind: AttachmentKind, val path: String, val fileName: String, val size: Long) {
    override fun toString() = "PreviewItem(<redacted>)"
}

/** Picked photos and videos staged for one message; [progress] is local staging and transcoding work. */
@Immutable data class SendPreview(
    val items: ImmutableList<PreviewItem> = persistentListOf(),
    val preparing: Boolean = false,
    val progress: Float? = null,
    val caption: String = "",
    val problem: UiText? = null
) {
    override fun toString() = "SendPreview(<redacted>)"
}

enum class AttachmentKind { IMAGE, ANIMATED, VIDEO, FILE }

/** What the bubble of one attachment shows; mapped from the transfer state of the media repository. */
enum class AttachmentStatus {
    /** An untrusted sender: nothing is decoded or downloaded until the person taps. */
    HIDDEN,

    /** Not on this device yet; tapping downloads it. */
    AVAILABLE,
    LOADING,
    READY,

    /** Failed in a way a retry may fix. */
    FAILED,

    /** The 24-hour access window has passed. */
    EXPIRED,

    /** Removed by the sender. */
    REVOKED,

    /** This device holds no key for it (paired after the message was sent). */
    NOT_ON_DEVICE,

    /** A local send that is staged or uploading. */
    SENDING,

    /** A local send waiting for the connection; it goes out on its own. */
    QUEUED,

    /** A local send the SDK refused; the message offers retry and discard. */
    UNSENT
}

@Immutable data class AttachmentItem(
    val messageId: String,
    val index: Int,
    val kind: AttachmentKind,
    /** Peer-controlled; always rendered through the untrusted file-name primitive. */
    val fileName: String,
    val size: Long,
    /** Width over height from message data only, so the row never changes height after download. */
    val aspect: Float,
    val status: AttachmentStatus,
    /** The decrypted file in app-private storage once [status] is READY. */
    val localPath: String? = null,
    val mime: String = ""
) {
    val key: String get() = messageId + "/" + index
    override fun toString() = "AttachmentItem(<redacted>)"
}
