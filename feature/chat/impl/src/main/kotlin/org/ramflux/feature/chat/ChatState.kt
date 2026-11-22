// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText
/** Timeline entries carry typed values; the screen formats times and dates for the current locale. */
@Immutable sealed interface TimelineItem {
    val id: String

    /** Creation time in epoch seconds, or null when the read model has none. */
    val sentAt: Long?
}

@Immutable data class CallItem(override val id: String, override val sentAt: Long?) : TimelineItem

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
    val attachments: ImmutableList<AttachmentItem> = persistentListOf()
) : TimelineItem {
    override fun toString() = "MessageItem(<redacted>)"
}

@Immutable data class ChatState(
    val conversationId: String = "",
    val groupId: String? = null,
    val title: UiText? = null,
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
    val viewerPosition: ViewerPosition? = null
) {
    override fun toString() = "ChatState(<redacted>)"
}

/** One conversation the chooser offers; [title] is peer-controlled. */
@Immutable data class ForwardRow(val conversation: String, val title: String) {
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
    SENDING
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
