// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.ramflux.data.ChatReaction
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitBubble
import org.ramflux.designsystem.kit.KitBubbleKind
import org.ramflux.designsystem.kit.KitChatHeader
import org.ramflux.designsystem.kit.KitComposer
import org.ramflux.designsystem.kit.KitContextMenu
import org.ramflux.designsystem.kit.KitDateChip
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitFailedMark
import org.ramflux.designsystem.kit.KitIconButton
import org.ramflux.designsystem.kit.KitMediaBubble
import org.ramflux.designsystem.kit.KitMenuEntry
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitReaction
import org.ramflux.designsystem.kit.KitReactionBar
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitUnreadDivider
import org.ramflux.designsystem.kit.KitZoomableImage
import org.ramflux.designsystem.kit.kitBubbleCap
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
import org.ramflux.media.rememberMediaImage

/**
 * The chat screen renders [state] and emits [ChatAction] only. It fills the space the caller gives it: it
 * applies no window insets and no width cap, so a shell or pane scene owns both. [rooted] hides the back
 * control when the chat is the detail pane beside its list. [clock] decides "today" for the date chips.
 */
@Composable
fun ChatScreen(
    state: ChatState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
    rooted: Boolean = false,
    clock: Clock = Clock.systemDefaultZone(),
    showDetails: Boolean = false,
    showCall: Boolean = false
) {
    val theme = LocalRFTheme.current
    val gap = theme.gap
    val stamps = rememberStamps(clock)
    val selected = state.selectedId?.let { id -> state.rows.firstOrNull { it.id == id } as? MessageItem }
    // Bubble bounds relative to this box, captured on long press so the actions attach to the pressed bubble.
    val anchors = remember { BubbleAnchors() }
    var attachMenu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize().background(theme.bg).onGloballyPositioned { anchors.root = it }) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(state, rooted, onAction, showDetails, showCall)
            state.headerProblem?.let { problem ->
                KitTipBar(problem.render(), "circle-alert", Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp))
            }
            if (state.queued) {
                KitTipBar(
                    stringResource(R.string.chat_offline_notice),
                    "wifi-off",
                    Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp).testTag("chat-offline")
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty()) {
                    EmptyTimeline(state, onAction, Modifier.align(Alignment.Center))
                } else {
                    Timeline(state, stamps, anchors, onAction)
                }
            }
            Notices(state)
            Composer(state, onAction) { attachMenu = true }
        }
        if (selected != null && actionsFor(selected).isNotEmpty()) {
            MessageActions(selected, state, stamps, anchors.pressed, onAction)
        }
        if (attachMenu) {
            AttachMenu(onAction) { attachMenu = false }
        }
        state.viewer?.let { Viewer(it, onAction) }
    }
}

/** Layout handles of the screen and the pressed bubble; not state, so writing them never recomposes. */
private class BubbleAnchors {
    var root: LayoutCoordinates? = null
    var pressed: Rect? = null

    fun capture(bubble: LayoutCoordinates?) {
        val screen = root
        pressed = if (bubble != null && screen != null && bubble.isAttached && screen.isAttached) {
            screen.localBoundingBoxOf(bubble)
        } else {
            null
        }
    }
}

@Composable
private fun ChatHeader(state: ChatState, rooted: Boolean, onAction: (ChatAction) -> Unit, showDetails: Boolean, showCall: Boolean) {
    val peerTitle = state.title?.render()?.let { Bidi.sanitize(it, UntrustedKind.Name) }?.takeIf { it.isNotBlank() }
    val title = peerTitle ?: stringResource(R.string.ui_conversation)
    val locale = LocalConfiguration.current.locales[0]
    val initial = peerTitle?.let { String(Character.toChars(it.codePointAt(0))).uppercase(locale) }
    val group = state.groupId != null
    KitChatHeader(
        title,
        null,
        {
            if (initial == null || group) {
                KitAvatar("", AVATAR, KitAvatarKind.Channel)
            } else {
                KitAvatar(initial, AVATAR, KitAvatarKind.Person)
            }
        },
        if (rooted) {
            null
        } else {
            { onAction(ChatAction.Back) }
        },
        onCall = if (showCall && !group) ({ onAction(ChatAction.Call) }) else null,
        onMore = if (showDetails) ({ onAction(ChatAction.Details) }) else null,
        backLabel = stringResource(R.string.ui_back),
        callLabel = stringResource(R.string.chat_call),
        moreLabel = stringResource(R.string.chat_conversation_details),
        tag = "chat-header"
    )
}

@Composable
private fun EmptyTimeline(state: ChatState, onAction: (ChatAction) -> Unit, modifier: Modifier) {
    val problem = state.problem
    val unavailable = state.unavailable
    when {
        problem != null -> KitEmpty(
            "circle-alert",
            stringResource(R.string.ui_unable_to_continue),
            problem.render(),
            modifier,
            action = stringResource(R.string.ui_refresh),
            onAction = { onAction(ChatAction.Refresh) }
        )
        state.loading -> KitEmpty(
            "loader-circle",
            stringResource(R.string.ui_loading_messages),
            stringResource(R.string.ui_reading_private_data),
            modifier
        )
        state.loaded -> KitEmpty(
            "message-square",
            stringResource(R.string.ui_no_messages),
            stringResource(R.string.ui_this_conversation_has_no_messages),
            modifier
        )
        unavailable != null -> KitEmpty("circle-alert", stringResource(R.string.ui_unable_to_continue), unavailable.render(), modifier)
        else -> Unit
    }
}

/** Timeline entries in reading order; the list draws them bottom-up so the newest message is in view. */
@Immutable
private sealed interface Entry {
    val key: String

    data class Day(val date: LocalDate) : Entry {
        override val key = "day:$date"
    }

    data object Unread : Entry {
        override val key = "unread"
    }

    data class Row(val item: TimelineItem) : Entry {
        override val key = "message:" + item.id
    }

    data object Typing : Entry {
        override val key = "typing"
    }

    data object Older : Entry {
        override val key = "older"
    }
}

/** An entry with the space above it, as a fraction of the layout gap: messages sit apart, chips and dividers close. */
@Immutable
private data class Placed(val entry: Entry, val space: Float)

private fun placed(entries: List<Entry>): List<Placed> = entries.mapIndexed { index, entry ->
    val previous = entries.getOrNull(index - 1)
    Placed(entry, if (entry is Entry.Row && previous is Entry.Row) MESSAGE_SPACE else CHIP_SPACE)
}

private fun entries(state: ChatState, zone: ZoneId): List<Entry> {
    val result = ArrayList<Entry>(state.rows.size + 8)
    if (state.loadingOlder) result += Entry.Older
    var day: LocalDate? = null
    state.rows.forEach { row ->
        val date = row.sentAt?.let { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }
        if (date != null && date != day) {
            result += Entry.Day(date)
            day = date
        }
        if (row.id == state.firstUnreadId) result += Entry.Unread
        result += Entry.Row(row)
    }
    if (state.peerTyping) result += Entry.Typing
    return result
}

@Composable
private fun Timeline(state: ChatState, stamps: Stamps, anchors: BubbleAnchors, onAction: (ChatAction) -> Unit) {
    val list = remember(state.rows, state.firstUnreadId, state.peerTyping, state.loadingOlder, stamps.zone) {
        placed(entries(state, stamps.zone)).asReversed()
    }
    val actionsLabel = stringResource(R.string.chat_message_actions)
    val listState = rememberLazyListState()
    // Reversed layout: index 0 is the newest entry at the bottom; the oldest loaded row has the last index.
    val newestShown by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val nearOldest by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - OLDER_PREFETCH
        }
    }
    val newestKey = list.firstOrNull()?.entry?.key
    LaunchedEffect(nearOldest, state.hasOlder, state.rows.size) {
        if (nearOldest && state.hasOlder && !state.loadingOlder) onAction(ChatAction.LoadOlder)
    }
    LaunchedEffect(newestShown, list) {
        val shown = (newestShown until list.size).firstNotNullOfOrNull { index ->
            ((list[index].entry as? Entry.Row)?.item as? MessageItem)?.takeIf { it.delivery == null }
        }
        if (shown != null) onAction(ChatAction.Shown(shown.id))
    }
    // A new row at the bottom stays in view while the reader is at the bottom; prepends never move it.
    LaunchedEffect(newestKey) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // The bubble cap follows the whole timeline width, as on the board, not the width inside the gutters.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = kitBubbleCap(maxWidth)
        TimelineList(list, listState, state, stamps, cap, actionsLabel, anchors, onAction)
    }
}

@Composable
private fun TimelineList(
    list: List<Placed>,
    listState: LazyListState,
    state: ChatState,
    stamps: Stamps,
    cap: Dp,
    actionsLabel: String,
    anchors: BubbleAnchors,
    onAction: (ChatAction) -> Unit
) {
    val gap = LocalRFTheme.current.gap
    LazyColumn(
        Modifier.fillMaxSize().testTag("chat-timeline"),
        state = listState,
        reverseLayout = true,
        verticalArrangement = Arrangement.Bottom
    ) {
        items(list, key = { it.entry.key }, contentType = { it.entry::class }) { placed ->
            val entry = placed.entry
            val padded = Modifier.fillMaxWidth().padding(start = gap.dp, end = gap.dp, top = (gap * placed.space).dp)
            when (entry) {
                is Entry.Day -> KitDateChip(
                    stamps.day(entry.date, stringResource(R.string.chat_date_today), stringResource(R.string.chat_date_yesterday)),
                    Modifier.padding(top = (gap * placed.space).dp)
                )
                Entry.Unread -> KitUnreadDivider(stringResource(R.string.chat_unread_divider), padded)
                Entry.Typing -> KitMeta(stringResource(R.string.ui_peer_typing), padded)
                Entry.Older -> KitMeta(stringResource(R.string.chat_loading_older), padded, centered = true)
                is Entry.Row -> when (val row = entry.item) {
                    is CallItem -> Column(padded, horizontalAlignment = Alignment.CenterHorizontally) {
                        KitMeta(stringResource(R.string.ui_call_record_unavailable), centered = true)
                        stamps.time(row.sentAt)?.let { KitMeta(it, centered = true) }
                    }
                    is MessageItem -> Message(row, state, stamps, cap, padded, actionsLabel, anchors, onAction)
                }
            }
        }
    }
}

@Composable
private fun Message(
    row: MessageItem,
    state: ChatState,
    stamps: Stamps,
    cap: Dp,
    modifier: Modifier,
    actionsLabel: String,
    anchors: BubbleAnchors,
    onAction: (ChatAction) -> Unit
) {
    val own = row.own
    val open = actionsFor(row).isNotEmpty()
    val bubble = remember(row.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val select = {
        anchors.capture(bubble[0])
        onAction(ChatAction.Select(row.id))
    }
    Box(modifier, contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.delivery == LocalDelivery.FAILED) {
                    KitFailedMark(stringResource(R.string.chat_not_sent))
                    Spacer(Modifier.width((LocalRFTheme.current.gap * .5f).dp))
                }
                val press = Modifier.onGloballyPositioned { bubble[0] = it }.then(
                    if (open) {
                        Modifier
                            .pointerInput(row.id) { detectTapGestures(onLongPress = { select() }) }
                            .semantics {
                                onLongClick(actionsLabel) {
                                    select()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    }
                )
                Column(
                    horizontalAlignment = if (own) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy((LocalRFTheme.current.gap * .25f).dp)
                ) {
                    row.attachments.forEach { item -> Attachment(item, own, stamps.time(row.sentAt), cap, onAction) }
                    val text = (row.content as? UiText.Data)?.value
                    if (row.attachments.isEmpty() || !text.isNullOrBlank()) {
                        Bubble(row, own, state.groupId != null, stamps, cap, press)
                    } else {
                        Box(press)
                    }
                }
            }
            if (row.reactions.isNotEmpty()) Reactions(row.reactions)
            if (row.delivery == LocalDelivery.FAILED) FailedLine(row.id, onAction)
        }
    }
}

/** One attachment: images and video as a media box sized from message data, everything else as a file row. */
@Composable
private fun Attachment(item: AttachmentItem, own: Boolean, time: String?, cap: Dp, onAction: (ChatAction) -> Unit) {
    val status = when (item.status) {
        AttachmentStatus.HIDDEN -> stringResource(R.string.chat_media_hidden)
        AttachmentStatus.AVAILABLE -> stringResource(R.string.chat_media_download)
        AttachmentStatus.LOADING -> stringResource(R.string.chat_media_loading)
        AttachmentStatus.READY -> null
        AttachmentStatus.FAILED -> stringResource(R.string.chat_media_failed)
        AttachmentStatus.EXPIRED -> stringResource(R.string.chat_media_expired)
        AttachmentStatus.REVOKED -> stringResource(R.string.chat_media_revoked)
        AttachmentStatus.NOT_ON_DEVICE -> stringResource(R.string.chat_media_not_on_device)
        AttachmentStatus.SENDING -> stringResource(R.string.chat_media_sending)
    }
    val icon = when (item.status) {
        AttachmentStatus.HIDDEN -> "eye"
        AttachmentStatus.AVAILABLE, AttachmentStatus.FAILED -> if (item.kind == AttachmentKind.FILE) "download" else "image-down"
        AttachmentStatus.LOADING, AttachmentStatus.SENDING -> "loader-circle"
        AttachmentStatus.EXPIRED -> "timer-off"
        AttachmentStatus.REVOKED, AttachmentStatus.NOT_ON_DEVICE -> "ban"
        AttachmentStatus.READY -> if (item.kind == AttachmentKind.VIDEO) "play" else null
    }
    val open = { onAction(ChatAction.OpenAttachment(item.key)) }
    val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, item.size)
    if (item.kind == AttachmentKind.FILE || (item.kind == AttachmentKind.VIDEO && item.status != AttachmentStatus.READY)) {
        KitBubble(
            "",
            Modifier.clickable(onClick = open).testTag("attachment-" + item.key),
            kind = KitBubbleKind.File,
            time = time,
            fileName = item.fileName.ifEmpty { stringResource(R.string.chat_attach_file) },
            fileMeta = if (status == null) size else stringResource(R.string.chat_file_meta, size, status),
            fileIcon = icon ?: "file-text",
            maxWidth = cap
        )
    } else {
        // Only files this device decrypted are decoded; senders' thumbnails are not in the message yet (R1).
        val path = item.localPath?.takeIf { item.status == AttachmentStatus.READY || item.status == AttachmentStatus.SENDING }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val target = with(density) { cap.roundToPx() }
        KitMediaBubble(
            rememberMediaImage(path, target),
            item.aspect,
            own,
            icon = icon,
            status = status,
            time = time,
            description = stringResource(if (item.kind == AttachmentKind.VIDEO) R.string.chat_media_video else R.string.chat_media_image),
            maxWidth = cap,
            onClick = open,
            tag = "attachment-" + item.key
        )
    }
}

@Composable
private fun Reactions(reactions: List<ReactionCount>) {
    val ack = stringResource(R.string.ui_reaction_ack)
    Row(
        Modifier.testTag("message-reaction-counts"),
        horizontalArrangement = Arrangement.spacedBy((LocalRFTheme.current.gap * .5f).dp)
    ) {
        reactions.forEach { reaction ->
            val token = if (reaction.token == ChatReaction.ACK) ack else Bidi.sanitize(reaction.token, UntrustedKind.Excerpt)
            KitMeta(
                if (reaction.count > 1) stringResource(R.string.chat_reaction_count, token, reaction.count) else token
            )
        }
    }
}

@Composable
private fun FailedLine(id: String, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    Row(
        Modifier.padding(top = (theme.gap * .25f).dp),
        horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KitMeta(stringResource(R.string.chat_not_sent), tone = theme.danger)
        KitMeta(stringResource(R.string.chat_meta_separator), tone = theme.danger)
        KitMeta(
            stringResource(R.string.chat_retry),
            Modifier.clickable { onAction(ChatAction.Retry(id)) }.testTag("chat-retry"),
            tone = theme.danger
        )
    }
}

@Composable
private fun Bubble(row: MessageItem, own: Boolean, group: Boolean, stamps: Stamps, cap: Dp?, modifier: Modifier = Modifier) {
    val status = when (row.delivery) {
        LocalDelivery.QUEUED -> stringResource(R.string.chat_queued)
        LocalDelivery.SENDING -> stringResource(R.string.chat_sending)
        LocalDelivery.FAILED, null -> stamps.time(row.sentAt)
    }
    KitBubble(
        row.content.render(),
        modifier,
        kind = if (own) KitBubbleKind.Own else KitBubbleKind.Incoming,
        time = status,
        edited = if (row.edited) stringResource(R.string.ui_edited) else null,
        sender = if (group && !own) row.sender else null,
        pending = row.delivery == LocalDelivery.QUEUED || row.delivery == LocalDelivery.SENDING,
        read = row.receipt == Receipt.READ,
        failed = row.delivery == LocalDelivery.FAILED,
        quote = row.replyPreview?.render(),
        ticks = row.receipt != null && row.delivery == null,
        receiptLabel = row.receipt?.let { stringResource(receiptLabel(it)) },
        maxWidth = cap,
        tag = row.id
    )
}

private fun receiptLabel(receipt: Receipt) = when (receipt) {
    Receipt.SENT -> R.string.ui_receipt_sent
    Receipt.DELIVERED -> R.string.ui_receipt_delivered
    Receipt.READ -> R.string.ui_receipt_read
}

@Composable
private fun Notices(state: ChatState) {
    val gap = LocalRFTheme.current.gap
    val failures =
        listOfNotNull(
            state.problem.takeIf {
                state.rows.isNotEmpty()
            },
            state.unavailable.takeIf { state.loaded || state.rows.isNotEmpty() },
            state.typingProblem
        )
    val notes = listOfNotNull(state.deliveryNotice)
    if (failures.isEmpty() && notes.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(horizontal = gap.dp, vertical = (gap * .5f).dp),
        verticalArrangement = Arrangement.spacedBy((gap * .5f).dp)
    ) {
        failures.forEach { KitTipBar(it.render(), "circle-alert") }
        notes.forEach { KitTipBar(it.render(), "info") }
    }
}

@Composable
private fun Composer(state: ChatState, onAction: (ChatAction) -> Unit, onAttach: () -> Unit) {
    val target = state.actionTarget?.let { id -> state.rows.firstOrNull { it.id == id } as? MessageItem }
    val author = when (state.composer) {
        ComposerMode.SEND -> null
        ComposerMode.EDIT -> stringResource(R.string.ui_editing)
        ComposerMode.REPLY -> target?.sender?.takeIf { it.isNotBlank() }?.let {
            uiText(R.string.chat_replying_to, UiText.Untrusted(it)).render()
        } ?: stringResource(R.string.ui_replying)
    }
    KitComposer(
        state.draft,
        { onAction(ChatAction.Draft(it)) },
        stringResource(R.string.chat_composer_placeholder),
        { onAction(ChatAction.Send) },
        onAttach,
        sendActive = state.canSend && !state.sending && state.draft.isNotBlank(),
        attachEnabled = state.canSend && !state.sending && state.composer == ComposerMode.SEND,
        replyAuthor = author,
        replyExcerpt = if (author == null) null else target?.content?.render(),
        onCloseReply = if (state.composer == ComposerMode.SEND) null else ({ onAction(ChatAction.CancelComposer) }),
        maxLines = COMPOSER_LINES,
        enabled = !state.sending,
        sendLabel = stringResource(R.string.ui_send),
        attachLabel = stringResource(R.string.ui_attach),
        closeLabel = stringResource(R.string.ui_cancel),
        tag = "composer"
    )
}

/** Photo picker (no storage permission) or document picker; both copy the item into the outbox. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.AttachMenu(onAction: (ChatAction) -> Unit, onClose: () -> Unit) {
    val theme = LocalRFTheme.current
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .testTag("attach-scrim")
    )
    KitContextMenu(
        listOf(
            KitMenuEntry(stringResource(R.string.chat_attach_photo), "images"),
            KitMenuEntry(stringResource(R.string.chat_attach_file), "paperclip")
        ),
        { index ->
            onClose()
            onAction(ChatAction.Pick(image = index == 0))
        },
        Modifier.align(Alignment.BottomStart).padding(theme.gap.dp),
        tag = "attach-menu"
    )
}

/** Full-screen image with zoom; save and share go through the system, the window keeps its secure flag. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Viewer(item: AttachmentItem, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.CloseViewer) }
    BoxWithConstraints(Modifier.matchParentSize().background(theme.bg).testTag("media-viewer")) {
        val target = with(androidx.compose.ui.platform.LocalDensity.current) { (maxOf(maxWidth, maxHeight) * 2).roundToPx() }
        rememberMediaImage(item.localPath, target)?.let {
            KitZoomableImage(it, stringResource(R.string.chat_media_image))
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(theme.gap.dp),
            horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp)
        ) {
            KitIconButton("download", stringResource(R.string.chat_media_save), { onAction(ChatAction.SaveAttachment(item.key)) })
            KitIconButton("forward", stringResource(R.string.chat_media_share), { onAction(ChatAction.ShareAttachment(item.key)) })
            KitIconButton("x", stringResource(R.string.chat_media_close), { onAction(ChatAction.CloseViewer) }, tag = "viewer-close")
        }
    }
}

/** The actions the read model and the SDK support for a message; nothing else is offered. */
private enum class MessageAction { REPLY, COPY, EDIT, DELETE_FOR_ME, DELETE_FOR_EVERYONE, DISCARD }

private fun actionsFor(row: MessageItem): List<MessageAction> = buildList {
    if (row.delivery != null) {
        // A local message the SDK has not accepted: it can be copied or dropped, nothing else.
        if (row.content is UiText.Data) add(MessageAction.COPY)
        if (row.delivery != LocalDelivery.SENDING) add(MessageAction.DISCARD)
        return@buildList
    }
    if (!row.deleted && !row.placeholder) add(MessageAction.REPLY)
    if (!row.deleted && !row.placeholder && row.content is UiText.Data) add(MessageAction.COPY)
    if (row.own && !row.deleted && !row.placeholder && !row.service && row.content is UiText.Data) add(MessageAction.EDIT)
    if (!row.deleted) {
        add(MessageAction.DELETE_FOR_ME)
        // Only the author can erase a message for everyone.
        if (row.own) add(MessageAction.DELETE_FOR_EVERYONE)
    }
}

/** Quick reactions shown on board MOB-B03; the SDK accepts any single grapheme. */
private val QUICK_REACTIONS = listOf(
    intArrayOf(0x1F44D),
    intArrayOf(0x2764, 0xFE0F),
    intArrayOf(0x1F602),
    intArrayOf(0x1F62E),
    intArrayOf(0x1F622),
    intArrayOf(0x1F64F)
).map { String(it, 0, it.size) }

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MessageActions(
    row: MessageItem,
    state: ChatState,
    stamps: Stamps,
    anchor: Rect?,
    onAction: (ChatAction) -> Unit
) {
    val theme = LocalRFTheme.current
    val gap = theme.gap
    val own = row.own
    val actions = actionsFor(row)
    val entries = actions.map { action ->
        when (action) {
            MessageAction.REPLY -> KitMenuEntry(stringResource(R.string.ui_reply), "reply")
            MessageAction.COPY -> KitMenuEntry(stringResource(R.string.chat_action_copy), "copy")
            MessageAction.EDIT -> KitMenuEntry(stringResource(R.string.ui_edit), "pencil")
            MessageAction.DELETE_FOR_ME -> KitMenuEntry(
                stringResource(R.string.ui_delete_for_me),
                "trash-2",
                danger = true,
                groupBefore = true
            )
            MessageAction.DELETE_FOR_EVERYONE -> KitMenuEntry(stringResource(R.string.ui_delete_for_everyone), "trash-2", danger = true)
            MessageAction.DISCARD -> KitMenuEntry(stringResource(R.string.chat_discard), "trash-2", danger = true, groupBefore = true)
        }
    }
    val reactionLabels = QUICK_REACTIONS.map { stringResource(R.string.chat_react_with, it) }
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onAction(ChatAction.Dismiss) }
            .testTag("message-actions-scrim")
    )
    val reactions = !row.deleted && !row.placeholder && row.delivery == null
    AnchoredActions(
        anchor,
        own,
        gap.dp,
        Modifier.matchParentSize().testTag("message-actions")
    ) {
        if (reactions) {
            KitReactionBar(
                QUICK_REACTIONS.mapIndexed { index, glyph -> KitReaction(reactionLabels[index], glyph) },
                { index -> onAction(ChatAction.React(QUICK_REACTIONS[index])) },
                null,
                tag = "message-reactions"
            )
        } else {
            Spacer(Modifier)
        }
        Bubble(row, own, state.groupId != null, stamps, null)
        KitContextMenu(entries, { index ->
            onAction(
                when (actions[index]) {
                    MessageAction.REPLY -> ChatAction.Reply
                    MessageAction.COPY -> ChatAction.Copy
                    MessageAction.EDIT -> ChatAction.Edit
                    MessageAction.DELETE_FOR_ME -> ChatAction.DeleteForMe
                    MessageAction.DELETE_FOR_EVERYONE -> ChatAction.DeleteForEveryone
                    MessageAction.DISCARD -> ChatAction.Discard(row.id)
                }
            )
        }, tag = "message-menu")
    }
}

/**
 * Places the reaction bar, the bubble copy and the menu so the copy covers the pressed bubble (board
 * MOB-B03), shifted only as far as needed to keep the whole group on screen. Without an anchor the group
 * sits at the bottom edge.
 */
@Composable
private fun AnchoredActions(anchor: Rect?, own: Boolean, gap: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val inner = loose.copy(maxWidth = (constraints.maxWidth - 2 * gap.roundToPx()).coerceAtLeast(0))
        val placeables = measurables.map { it.measure(inner) }
        val (bar, copy, menu) = placeables
        val spacing = (gap / 2).roundToPx()
        val edge = gap.roundToPx()
        val total = bar.height + spacing + copy.height + spacing + menu.height
        val bubbleTop = anchor?.top?.toInt() ?: (constraints.maxHeight - edge - menu.height - spacing - copy.height)
        val desired = bubbleTop - bar.height - spacing
        val top = desired.coerceAtMost(constraints.maxHeight - edge - total).coerceAtLeast(edge)
        layout(constraints.maxWidth, constraints.maxHeight) {
            fun x(width: Int) = if (own) constraints.maxWidth - edge - width else edge
            var y = top
            bar.placeRelative(x(bar.width), y)
            y += bar.height + spacing
            copy.placeRelative(x(copy.width), y)
            y += copy.height + spacing
            menu.placeRelative(x(menu.width), y)
        }
    }
}

/** Locale-aware time-only stamps and date chip labels, formatted from typed epoch seconds. */
@Immutable
private class Stamps(private val clock: Clock, locale: Locale, hour24: Boolean) {
    val zone: ZoneId = clock.zone

    // The device 12/24-hour setting decides the hour cycle; the locale decides the pattern around it.
    private val timeFormat = DateTimeFormatter.ofPattern(
        android.text.format.DateFormat.getBestDateTimePattern(locale, if (hour24) HOUR_24 else HOUR_12),
        locale
    )
    private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)

    fun time(epochSeconds: Long?): String? = epochSeconds?.let { Instant.ofEpochSecond(it).atZone(zone).format(timeFormat) }

    fun day(date: LocalDate, today: String, yesterday: String): String {
        val now = LocalDate.now(clock)
        return when (date) {
            now -> today
            now.minusDays(1) -> yesterday
            else -> date.format(dateFormat)
        }
    }
}

@Composable
private fun rememberStamps(clock: Clock): Stamps {
    val locale = LocalConfiguration.current.locales[0]
    val hour24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    return remember(clock, locale, hour24) { Stamps(clock, locale, hour24) }
}

private const val AVATAR = 36f
private const val COMPOSER_LINES = 4
private const val MESSAGE_SPACE = .625f
private const val CHIP_SPACE = .125f
private const val OLDER_PREFETCH = 8

// Skeletons for the best localized pattern: hour (0-23) or hour (1-12) with minutes.
private val HOUR_24 = charArrayOf('H', 'm').concatToString()
private const val HOUR_12 = "hm"
