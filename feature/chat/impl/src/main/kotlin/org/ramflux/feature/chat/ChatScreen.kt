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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import org.ramflux.data.IdentityReviewError
import org.ramflux.data.IdentityState
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitBubble
import org.ramflux.designsystem.kit.KitBubbleKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitChatHeader
import org.ramflux.designsystem.kit.KitComposer
import org.ramflux.designsystem.kit.KitContextMenu
import org.ramflux.designsystem.kit.KitDateChip
import org.ramflux.designsystem.kit.KitEmpty
import org.ramflux.designsystem.kit.KitFailedMark
import org.ramflux.designsystem.kit.KitIdentityChangeSheet
import org.ramflux.designsystem.kit.KitIdentityNotice
import org.ramflux.designsystem.kit.KitMenuEntry
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitReaction
import org.ramflux.designsystem.kit.KitReactionBar
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSecurityWarning
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitUnreadDivider
import org.ramflux.designsystem.kit.KitWarningTone
import org.ramflux.designsystem.kit.kitBubbleCap
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R
import org.ramflux.media.AnimationSlots
import org.ramflux.media.LocalAnimationSlots

/**
 * The chat screen renders [state] and emits [ChatAction] only. It fills the space the caller gives it: it
 * applies no window insets and no width cap, so a shell or pane scene owns both. [rooted] hides the back
 * control when the chat is the detail pane beside its list. [clock] decides "today" for the date chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
    rooted: Boolean = false,
    clock: Clock = Clock.systemDefaultZone(),
    showDetails: Boolean = false,
    showCall: Boolean = false,
    showCamera: Boolean = false,
    bottomInset: WindowInsets = WindowInsets(0),
    showStorage: Boolean = false
) {
    val theme = LocalRFTheme.current
    val gap = theme.gap
    val stamps = rememberStamps(clock)
    val selected = state.selectedId?.let { id -> state.rows.firstOrNull { it.id == id } as? MessageItem }
    // Bubble bounds relative to this box, captured on long press so the actions attach to the pressed bubble.
    val anchors = remember { BubbleAnchors() }
    var attachMenu by remember { mutableStateOf(false) }
    // The attach menu opens at the bottom edge: the keyboard is closed first, or it would cover the menu.
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var chatMenu by remember { mutableStateOf(false) }
    // Animated images share two playing slots per chat (media design 4.2).
    val slots = remember { AnimationSlots() }
    CompositionLocalProvider(LocalAnimationSlots provides slots) {
        Box(modifier.fillMaxSize().background(theme.bg).onGloballyPositioned { anchors.root = it }) {
            Column(Modifier.fillMaxSize()) {
                ChatHeader(state, rooted, onAction, showCall) { chatMenu = true }
                state.headerProblem?.let { problem ->
                    KitTipBar(problem.render(), "circle-alert", Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp))
                }
                if (state.groupId == null && state.identityState == IdentityState.CHANGED) {
                    IdentityBanner(state, onAction)
                }
                if (state.queued) {
                    KitTipBar(
                        stringResource(R.string.chat_offline_notice),
                        "wifi-off",
                        Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp).testTag("chat-offline")
                    )
                }
                val mls = state.mls
                if (mls == null) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { TimelineBody(state, stamps, anchors, onAction) }
                } else {
                    // An MLS group chat: pull to refresh calls `group.refresh`.
                    PullToRefreshBox(
                        isRefreshing = mls.refreshing,
                        onRefresh = { onAction(ChatAction.Refresh) },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) { Box(Modifier.fillMaxSize()) { TimelineBody(state, stamps, anchors, onAction) } }
                }
                if (mls?.trimmed == true) {
                    KitButton(
                        stringResource(R.string.group_chat_jump_newest),
                        { onAction(ChatAction.JumpToNewest) },
                        Modifier.fillMaxWidth().padding(horizontal = gap.dp, vertical = (gap * .5f).dp),
                        kind = KitButtonKind.Secondary,
                        tag = "mls-jump-newest"
                    )
                }
                Notices(state)
                mls?.notice?.let {
                    KitTipBar(
                        groupDeliveryText(it),
                        "circle-alert",
                        Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp),
                        tag = "mls-notice"
                    )
                }
                if (state.sendBlocked && state.identityState == IdentityState.CHANGED) IdentityComposerPrompt(state, onAction)
                when {
                    mls?.readOnly != null -> KitTipBar(
                        stringResource(
                            when (mls.readOnly) {
                                GroupReadOnly.LEFT -> R.string.group_state_left
                                GroupReadOnly.REMOVED -> R.string.group_state_removed
                                GroupReadOnly.BANNED -> R.string.group_state_banned
                            }
                        ),
                        "info",
                        Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp),
                        tag = "mls-readonly"
                    )
                    mls?.limitedUntil != null -> KitTipBar(
                        groupDeliveryText(GroupDelivery(GroupDeliveryKind.NOT_SENT, GroupReason.RATE_LIMITED, mls.limitedUntil)),
                        "info",
                        Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp),
                        tag = "mls-limited"
                    )
                    else -> Composer(state, onAction, bottomInset) {
                        keyboard?.hide()
                        focus.clearFocus()
                        attachMenu = true
                    }
                }
            }
            if (selected != null && actionsFor(selected, state.mls != null).isNotEmpty()) {
                MessageActions(selected, state, stamps, anchors.pressed, onAction)
            }
            if (attachMenu) {
                AttachMenu(showCamera, onAction) { attachMenu = false }
            }
            state.viewer?.let { Viewer(it, state.viewerPosition, onAction) }
            if (chatMenu) ChatMenu(showDetails, showStorage, state.mls != null, onAction) { chatMenu = false }
            if (state.mls?.confirmDelete == true) GroupDeleteConfirm(onAction)
            state.mls?.mention?.let { MentionPicker(it, onAction) }
            state.gallery?.let { Gallery(it, onAction) }
            state.preview?.let { SendPreviewSheet(it, onAction) }
            state.forward?.let { ForwardChooser(it, onAction) }
            state.review?.let { sheet ->
                KitIdentityChangeSheet(
                    name = state.peerName.orEmpty(),
                    username = sheet.username,
                    ai = sheet.aiFlag,
                    accepting = sheet.accepting,
                    waiting = sheet.newAnchor == null,
                    notice = when (sheet.error) {
                        IdentityReviewError.ALREADY_HANDLED -> KitIdentityNotice.AlreadyHandled
                        IdentityReviewError.CHANGED_AGAIN -> KitIdentityNotice.ChangedAgain
                        IdentityReviewError.FAILED -> KitIdentityNotice.Failed
                        null -> null
                    },
                    onAccept = { onAction(ChatAction.AcceptIdentity) },
                    onLater = { onAction(ChatAction.LaterIdentity) }
                )
            }
        }
    }
}

/** The empty state or the timeline; the same body for every chat. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TimelineBody(
    state: ChatState,
    stamps: Stamps,
    anchors: BubbleAnchors,
    onAction: (ChatAction) -> Unit
) {
    if (state.rows.isEmpty()) {
        EmptyTimeline(state, onAction, Modifier.align(Alignment.Center))
    } else {
        Timeline(state, stamps, anchors, onAction)
    }
}

/** The identity banner (board B10 look): shown while the peer's identity is changed. It has no dismiss; only a review ends it. */
@Composable
private fun IdentityBanner(state: ChatState, onAction: (ChatAction) -> Unit) {
    val gap = LocalRFTheme.current.gap
    Column(
        Modifier.fillMaxWidth().padding(horizontal = gap.dp, vertical = (gap * .5f).dp).testTag("identity-banner"),
        verticalArrangement = Arrangement.spacedBy((gap * .5f).dp)
    ) {
        KitSecurityWarning(
            stringResource(R.string.identity_state_changed),
            uiText(R.string.identity_changed_banner, UiText.Untrusted(state.peerName.orEmpty())).render(),
            KitWarningTone.Warning
        )
        KitButton(
            stringResource(R.string.identity_changed_review),
            { onAction(ChatAction.ReviewIdentity) },
            kind = KitButtonKind.Secondary,
            tag = "identity-review"
        )
    }
}

/** Above the composer after `identity_change_pending`: the message is kept as a failed bubble and is never sent again by itself. */
@Composable
private fun IdentityComposerPrompt(state: ChatState, onAction: (ChatAction) -> Unit) {
    val gap = LocalRFTheme.current.gap
    Column(
        Modifier.fillMaxWidth().padding(horizontal = gap.dp, vertical = (gap * .5f).dp).testTag("identity-composer-prompt"),
        verticalArrangement = Arrangement.spacedBy((gap * .5f).dp)
    ) {
        KitSecurityWarning(
            stringResource(R.string.identity_state_changed),
            uiText(R.string.identity_send_blocked, UiText.Untrusted(state.peerName.orEmpty())).render(),
            KitWarningTone.Warning
        )
        KitButton(
            stringResource(R.string.identity_changed_review),
            { onAction(ChatAction.ReviewIdentity) },
            kind = KitButtonKind.Secondary,
            tag = "identity-review-composer"
        )
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
private fun ChatHeader(state: ChatState, rooted: Boolean, onAction: (ChatAction) -> Unit, showCall: Boolean, onMenu: () -> Unit) {
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
        onMore = if (state.loaded) onMenu else null,
        backLabel = stringResource(R.string.ui_back),
        callLabel = stringResource(R.string.chat_call),
        moreLabel = stringResource(R.string.chat_conversation_menu),
        tag = "chat-header",
        ai = state.titleAi && !group
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
            ((list[index].entry as? Entry.Row)?.item as? MessageItem)?.takeIf {
                it.delivery == null && !it.id.startsWith(IDENTITY_LINE)
            }
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
                    // Group service rows (member added, name changed, ...) are centred lines, never a bubble.
                    is MessageItem -> if (row.service && !row.deleted) {
                        Column(padded.testTag("service-" + row.id), horizontalAlignment = Alignment.CenterHorizontally) {
                            KitMeta(row.line?.let { groupLineText(it) } ?: row.content.render(), centered = true)
                        }
                    } else {
                        Message(row, state, stamps, cap, padded, actionsLabel, anchors, onAction)
                    }
                    is CardItem -> CardView(row, cap, padded, onAction)
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
    val open = actionsFor(row, state.mls != null).isNotEmpty()
    val bubble = remember(row.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val select = {
        anchors.capture(bubble[0])
        onAction(ChatAction.Select(row.id))
    }
    Box(modifier, contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.delivery == LocalDelivery.FAILED || row.groupDelivery?.kind == GroupDeliveryKind.NOT_SENT) {
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
                    Attachments(row.attachments, own, stamps.time(row.sentAt), cap, onAction)
                    val text = (row.content as? UiText.Data)?.value
                    if (row.attachments.isEmpty() || !text.isNullOrBlank()) {
                        Bubble(row, own, state.groupId != null, stamps, cap, press)
                    } else {
                        Box(press)
                    }
                }
            }
            if (row.reactions.isNotEmpty()) Reactions(row.reactions)
            if (row.groupDelivery != null) {
                GroupStateLine(row, state.mls?.limitedUntil != null, onAction)
            } else if (row.delivery == LocalDelivery.FAILED) {
                FailedLine(row.id, onAction)
            }
        }
    }
}

/** The state line under an MLS group row; a refused or failed send offers Retry, an explicit tap that sends under a new local id. */
@Composable
private fun GroupStateLine(row: MessageItem, locked: Boolean, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    val delivery = row.groupDelivery ?: return
    val failed = delivery.kind == GroupDeliveryKind.NOT_SENT
    Row(
        Modifier.padding(top = (theme.gap * .25f).dp),
        horizontalArrangement = Arrangement.spacedBy((theme.gap * .5f).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KitMeta(groupDeliveryText(delivery), tone = if (failed) theme.danger else null, tag = "mls-send-state")
        if (failed) {
            KitMeta(
                stringResource(R.string.group_send_retry),
                // Retry is off while the composer is locked by the daily limit.
                (if (locked) Modifier else Modifier.clickable { onAction(ChatAction.Retry(row.id)) }).testTag("mls-retry"),
                tone = if (locked) null else theme.danger
            )
        }
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
        sender = if (group && !own) row.sender.ifBlank { stringResource(R.string.chat_member_fallback) } else null,
        agentTag = if (group && !own && row.senderAi) stringResource(org.ramflux.core.designsystem.R.string.kit_ai_marker) else null,
        pending = row.delivery == LocalDelivery.QUEUED || row.delivery == LocalDelivery.SENDING ||
            (row.groupDelivery != null && row.groupDelivery.kind != GroupDeliveryKind.NOT_SENT),
        read = row.receipt == Receipt.READ,
        failed = row.delivery == LocalDelivery.FAILED || row.groupDelivery?.kind == GroupDeliveryKind.NOT_SENT,
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
private fun Composer(state: ChatState, onAction: (ChatAction) -> Unit, bottomInset: WindowInsets, onAttach: () -> Unit) {
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
        tag = "composer",
        bottomInset = bottomInset
    )
}

/**
 * Photo picker (no storage permission), the system camera (after the permission flow) or the document
 * picker; each copies the item into the outbox.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.AttachMenu(
    showCamera: Boolean,
    onAction: (ChatAction) -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalRFTheme.current
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .testTag("attach-scrim")
    )
    val sources = listOfNotNull(PickSource.PHOTO, PickSource.CAMERA.takeIf { showCamera }, PickSource.FILE)
    KitContextMenu(
        sources.map { source ->
            when (source) {
                PickSource.PHOTO -> KitMenuEntry(stringResource(R.string.chat_attach_photo), "images")
                PickSource.CAMERA -> KitMenuEntry(stringResource(R.string.chat_attach_camera), "camera")
                PickSource.FILE -> KitMenuEntry(stringResource(R.string.chat_attach_file), "paperclip")
            }
        },
        { index ->
            onClose()
            onAction(ChatAction.Pick(sources[index]))
        },
        Modifier.align(Alignment.BottomStart).padding(theme.gap.dp),
        tag = "attach-menu"
    )
}

/** The conversation menu: media and files, storage, and details when the host has a details screen. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ChatMenu(
    showDetails: Boolean,
    showStorage: Boolean,
    group: Boolean,
    onAction: (ChatAction) -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalRFTheme.current
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .testTag("chat-menu-scrim")
    )
    val actions = listOfNotNull(
        ChatAction.OpenGallery.takeIf { !group },
        ChatAction.MentionOpen.takeIf { group },
        ChatAction.Refresh.takeIf { group },
        ChatAction.Storage.takeIf { showStorage && !group },
        ChatAction.Details.takeIf { showDetails }
    )
    KitContextMenu(
        actions.map { action ->
            when (action) {
                ChatAction.Storage -> KitMenuEntry(stringResource(R.string.storage_title), "hard-drive")
                ChatAction.Details -> KitMenuEntry(stringResource(R.string.chat_conversation_details), "info")
                ChatAction.MentionOpen -> KitMenuEntry(stringResource(R.string.group_chat_mention), "at-sign")
                ChatAction.Refresh -> KitMenuEntry(stringResource(R.string.group_chat_refresh), "refresh-cw")
                else -> KitMenuEntry(stringResource(R.string.gallery_title), "layout-grid")
            }
        },
        { index ->
            onClose()
            onAction(actions[index])
        },
        Modifier.align(Alignment.TopEnd).padding(theme.gap.dp),
        tag = "chat-menu"
    )
}

/**
 * The forward chooser over the timeline: a lazy, keyed list of direct conversations that loads the next
 * directory page near its end. Titles are peer-controlled names.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ForwardChooser(sheet: ForwardSheet, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.CloseForward) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - OLDER_PREFETCH
        }
    }
    LaunchedEffect(nearEnd, sheet.hasMore, sheet.rows.size) {
        if (nearEnd && sheet.hasMore && !sheet.loading) onAction(ChatAction.ForwardMore)
    }
    Column(Modifier.matchParentSize().background(theme.bg).testTag("forward-chooser")) {
        KitPageHeader(
            stringResource(R.string.chat_forward_title),
            actionLabel = stringResource(R.string.chat_forward_close),
            onAction = { onAction(ChatAction.CloseForward) },
            tag = "forward-header"
        )
        sheet.problem?.let { KitTipBar(it.render(), "circle-alert", Modifier.padding(theme.gap.dp)) }
        if (sheet.sending) KitMeta(stringResource(R.string.chat_forward_sending), Modifier.padding(theme.gap.dp), centered = true)
        when {
            sheet.rows.isEmpty() && sheet.loading -> KitMeta(
                stringResource(R.string.ui_loading_messages),
                Modifier.fillMaxWidth().padding(theme.gap.dp),
                centered = true
            )
            sheet.rows.isEmpty() && sheet.problem == null -> KitEmpty("forward", stringResource(R.string.chat_forward_empty), "")
            else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                items(sheet.rows, key = { it.conversation }, contentType = { "target" }) { row ->
                    val name = row.title.ifBlank { stringResource(R.string.ui_conversation) }
                    KitRow(
                        name,
                        { onAction(ChatAction.ForwardTo(row.conversation)) },
                        enabled = !sheet.sending,
                        trailing = KitRowTrailing.None,
                        peer = row.title.isNotBlank(),
                        testTag = "forward-" + row.conversation
                    )
                }
            }
        }
    }
}

/** The actions the read model and the SDK support for a message; nothing else is offered. */
private enum class MessageAction { REPLY, FORWARD, COPY, EDIT, DELETE_FOR_ME, DELETE_FOR_EVERYONE, DISCARD }

private fun actionsFor(row: MessageItem, group: Boolean = false): List<MessageAction> = buildList {
    if (group) {
        // An MLS group row: copy for any text; Discard only for a send that was not accepted; "Delete for everyone" for an
        // own posted row. A queued or delayed post belongs to the SDK and can be neither dropped nor deleted here.
        val state = row.groupDelivery
        val text = row.content is UiText.Data && row.line == null
        if (state != null || row.delivery != null) {
            if (text) add(MessageAction.COPY)
            if (state?.kind == GroupDeliveryKind.NOT_SENT) add(MessageAction.DISCARD)
            return@buildList
        }
        if (row.line != null || row.deleted) return@buildList
        add(MessageAction.REPLY)
        if (text) add(MessageAction.COPY)
        if (row.deletable) add(MessageAction.DELETE_FOR_EVERYONE)
        return@buildList
    }
    if (row.delivery != null) {
        // A local message the SDK has not accepted: it can be copied or dropped, nothing else.
        if (row.content is UiText.Data) add(MessageAction.COPY)
        if (row.delivery != LocalDelivery.SENDING) add(MessageAction.DISCARD)
        return@buildList
    }
    if (!row.deleted && !row.placeholder) add(MessageAction.REPLY)
    if (forwardable(row)) add(MessageAction.FORWARD)
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
    val actions = actionsFor(row, state.mls != null)
    val entries = actions.map { action ->
        when (action) {
            MessageAction.REPLY -> KitMenuEntry(stringResource(R.string.ui_reply), "reply")
            MessageAction.FORWARD -> KitMenuEntry(stringResource(R.string.chat_action_forward), "forward")
            MessageAction.COPY -> KitMenuEntry(stringResource(R.string.chat_action_copy), "copy")
            MessageAction.EDIT -> KitMenuEntry(stringResource(R.string.ui_edit), "pencil")
            MessageAction.DELETE_FOR_ME -> KitMenuEntry(
                stringResource(R.string.ui_delete_for_me),
                "trash-2",
                danger = true,
                groupBefore = true
            )
            MessageAction.DELETE_FOR_EVERYONE -> KitMenuEntry(
                stringResource(if (state.mls != null) R.string.group_delete_action else R.string.ui_delete_for_everyone),
                "trash-2",
                danger = true
            )
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
    val reactions = !row.deleted && !row.placeholder && row.delivery == null && state.mls == null
    var picker by remember(row.id) { mutableStateOf(false) }
    if (picker) {
        EmojiSheet({ onAction(ChatAction.React(it)) }, { picker = false })
        return
    }
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
                { picker = true },
                moreLabel = stringResource(R.string.chat_react_more),
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
                    MessageAction.FORWARD -> ChatAction.Forward()
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
 * The platform emoji picker (androidx.emoji2) for any reaction; the view model accepts one grapheme the
 * SDK takes. The picker view draws with the system emoji font and stays inside this window.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EmojiSheet(onPick: (String) -> Unit, onClose: () -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler(onBack = onClose)
    Column(Modifier.matchParentSize().background(theme.bg).testTag("emoji-picker")) {
        KitPageHeader(stringResource(R.string.chat_react_more), actionLabel = stringResource(R.string.ui_cancel), onAction = onClose)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { context ->
                androidx.emoji2.emojipicker.EmojiPickerView(context).apply {
                    setOnEmojiPickedListener { picked -> onPick(picked.emoji) }
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
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

/** The confirmation of "Delete for everyone" in an MLS group: nothing is deleted until the Delete tap. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.GroupDeleteConfirm(onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.CancelDelete) }
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onAction(ChatAction.CancelDelete) }
            .testTag("mls-delete-scrim")
    )
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(theme.overlay).padding(theme.gap.dp).testTag("mls-delete-confirm"),
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        KitMeta(stringResource(R.string.group_delete_title))
        KitButton(
            stringResource(R.string.group_delete_confirm),
            { onAction(ChatAction.ConfirmDelete) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Destructive,
            tag = "mls-delete"
        )
        KitButton(
            stringResource(R.string.group_delete_cancel),
            { onAction(ChatAction.CancelDelete) },
            Modifier.fillMaxWidth(),
            kind = KitButtonKind.Secondary,
            tag = "mls-delete-cancel"
        )
    }
}

/** The mention picker: the loaded members of the group, paged lazily; a pick puts the name in the draft. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MentionPicker(sheet: MentionSheet, onAction: (ChatAction) -> Unit) {
    val theme = LocalRFTheme.current
    BackHandler { onAction(ChatAction.MentionClose) }
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - OLDER_PREFETCH
        }
    }
    LaunchedEffect(nearEnd, sheet.hasMore, sheet.rows.size) {
        if (nearEnd && sheet.hasMore && !sheet.loading) onAction(ChatAction.MentionMore)
    }
    Column(Modifier.matchParentSize().background(theme.bg).testTag("mls-mention-picker")) {
        KitPageHeader(
            stringResource(R.string.group_chat_mention),
            actionLabel = stringResource(R.string.group_chat_mention_close),
            onAction = { onAction(ChatAction.MentionClose) },
            tag = "mls-mention-header"
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(sheet.rows, key = { it.anchor }, contentType = { "member" }) { row ->
                KitRow(
                    row.display ?: stringResource(R.string.chat_member_fallback),
                    { onAction(ChatAction.MentionPick(row.anchor)) },
                    trailing = KitRowTrailing.None,
                    peer = row.display != null,
                    ai = row.ai,
                    testTag = "mls-mention-row"
                )
            }
        }
    }
}
