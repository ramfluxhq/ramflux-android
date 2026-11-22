// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import org.ramflux.designsystem.kit.KitMenuEntry
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitReaction
import org.ramflux.designsystem.kit.KitReactionBar
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.KitUnreadDivider
import org.ramflux.designsystem.kit.kitBubbleCap
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

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
    clock: Clock = Clock.systemDefaultZone()
) {
    val theme = LocalRFTheme.current
    val gap = theme.gap
    val stamps = rememberStamps(clock)
    val selected = state.selectedId?.let { id -> state.rows.firstOrNull { it.id == id } as? MessageItem }
    Box(modifier.fillMaxSize().background(theme.bg)) {
        Column(Modifier.fillMaxSize()) {
            ChatHeader(state, rooted, onAction)
            state.headerProblem?.let { problem ->
                KitTipBar(problem.render(), "circle-alert", Modifier.padding(horizontal = gap.dp, vertical = (gap * .5f).dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.rows.isEmpty()) {
                    EmptyTimeline(state, onAction, Modifier.align(Alignment.Center))
                } else {
                    Timeline(state, stamps, onAction)
                }
            }
            Notices(state)
            Composer(state, onAction)
        }
        if (selected != null && actionsFor(selected).isNotEmpty()) {
            MessageActions(selected, state, stamps, onAction)
        }
    }
}

@Composable
private fun ChatHeader(state: ChatState, rooted: Boolean, onAction: (ChatAction) -> Unit) {
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
        backLabel = stringResource(R.string.ui_back),
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
}

/** An entry with the space above it, as a fraction of the layout gap: messages sit apart, chips and dividers close. */
@Immutable
private data class Placed(val entry: Entry, val space: Float)

private fun placed(entries: List<Entry>): List<Placed> = entries.mapIndexed { index, entry ->
    val previous = entries.getOrNull(index - 1)
    Placed(entry, if (entry is Entry.Row && previous is Entry.Row) MESSAGE_SPACE else CHIP_SPACE)
}

private fun entries(state: ChatState, zone: ZoneId): List<Entry> {
    val result = mutableListOf<Entry>()
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
private fun Timeline(state: ChatState, stamps: Stamps, onAction: (ChatAction) -> Unit) {
    val list = remember(state.rows, state.firstUnreadId, state.peerTyping, stamps.zone) { placed(entries(state, stamps.zone)).asReversed() }
    val actionsLabel = stringResource(R.string.chat_message_actions)
    // The bubble cap follows the whole timeline width, as on the board, not the width inside the gutters.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cap = kitBubbleCap(maxWidth)
        TimelineList(list, state, stamps, cap, actionsLabel, onAction)
    }
}

@Composable
private fun TimelineList(
    list: List<Placed>,
    state: ChatState,
    stamps: Stamps,
    cap: Dp,
    actionsLabel: String,
    onAction: (ChatAction) -> Unit
) {
    val gap = LocalRFTheme.current.gap
    LazyColumn(
        Modifier.fillMaxSize().testTag("chat-timeline"),
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
                is Entry.Row -> when (val row = entry.item) {
                    is CallItem -> Column(padded, horizontalAlignment = Alignment.CenterHorizontally) {
                        KitMeta(stringResource(R.string.ui_call_record_unavailable), centered = true)
                        stamps.time(row.sentAt)?.let { KitMeta(it, centered = true) }
                    }
                    is MessageItem -> Message(row, state, stamps, cap, padded, actionsLabel, onAction)
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
    onAction: (ChatAction) -> Unit
) {
    val own = row.own
    val open = actionsFor(row).isNotEmpty()
    Box(modifier, contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(horizontalAlignment = if (own) Alignment.End else Alignment.Start) {
            Bubble(
                row,
                own,
                state.groupId != null,
                stamps,
                cap,
                if (open) {
                    Modifier
                        .pointerInput(row.id) { detectTapGestures(onLongPress = { onAction(ChatAction.Select(row.id)) }) }
                        .semantics {
                            onLongClick(actionsLabel) {
                                onAction(ChatAction.Select(row.id))
                                true
                            }
                        }
                } else {
                    Modifier
                }
            )
            row.reaction?.let { KitMeta(Bidi.sanitize(it.render(), UntrustedKind.Excerpt)) }
        }
    }
}

@Composable
private fun Bubble(row: MessageItem, own: Boolean, group: Boolean, stamps: Stamps, cap: Dp?, modifier: Modifier = Modifier) {
    KitBubble(
        row.content.render(),
        modifier,
        kind = if (own) KitBubbleKind.Own else KitBubbleKind.Incoming,
        time = stamps.time(row.sentAt),
        edited = if (row.edited) stringResource(R.string.ui_edited) else null,
        sender = if (group && !own) row.sender else null,
        read = row.receipt == Receipt.READ,
        quote = row.replyPreview?.render(),
        ticks = row.receipt != null,
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
private fun Composer(state: ChatState, onAction: (ChatAction) -> Unit) {
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
        { onAction(ChatAction.Attachments) },
        sendActive = state.canSend && !state.sending && state.draft.isNotBlank(),
        attachEnabled = !state.loading && !state.sending,
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

/** The actions the read model and the SDK support for a message; nothing else is offered. */
private enum class MessageAction { REPLY, EDIT, DELETE_FOR_ME, DELETE_FOR_EVERYONE }

private fun actionsFor(row: MessageItem): List<MessageAction> = buildList {
    if (!row.deleted && !row.placeholder) add(MessageAction.REPLY)
    if (row.own && !row.deleted && !row.placeholder && !row.service && row.content is UiText.Data) add(MessageAction.EDIT)
    if (!row.deleted) {
        add(MessageAction.DELETE_FOR_ME)
        add(MessageAction.DELETE_FOR_EVERYONE)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.MessageActions(
    row: MessageItem,
    state: ChatState,
    stamps: Stamps,
    onAction: (ChatAction) -> Unit
) {
    val theme = LocalRFTheme.current
    val gap = theme.gap
    val own = row.own
    val actions = actionsFor(row)
    val entries = actions.map { action ->
        when (action) {
            MessageAction.REPLY -> KitMenuEntry(stringResource(R.string.ui_reply), "reply")
            MessageAction.EDIT -> KitMenuEntry(stringResource(R.string.ui_edit), "pencil")
            MessageAction.DELETE_FOR_ME -> KitMenuEntry(
                stringResource(R.string.ui_delete_for_me),
                "trash-2",
                danger = true,
                groupBefore = true
            )
            MessageAction.DELETE_FOR_EVERYONE -> KitMenuEntry(stringResource(R.string.ui_delete_for_everyone), "trash-2", danger = true)
        }
    }
    Box(
        Modifier
            .matchParentSize()
            .background(theme.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onAction(ChatAction.Dismiss) }
            .testTag("message-actions-scrim")
    )
    Column(
        Modifier.align(if (own) Alignment.BottomEnd else Alignment.BottomStart).padding(gap.dp).testTag("message-actions"),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy((gap * .5f).dp)
    ) {
        if (!row.deleted) {
            // The conversation ABI supports one reaction, the acknowledgement; no emoji set is offered.
            KitReactionBar(
                listOf(KitReaction(stringResource(R.string.ui_react), icon = "check")),
                { onAction(ChatAction.React) },
                null,
                tag = "message-reactions"
            )
        }
        Bubble(row, own, state.groupId != null, stamps, null)
        KitContextMenu(entries, { index ->
            onAction(
                when (actions[index]) {
                    MessageAction.REPLY -> ChatAction.Reply
                    MessageAction.EDIT -> ChatAction.Edit
                    MessageAction.DELETE_FOR_ME -> ChatAction.DeleteForMe
                    MessageAction.DELETE_FOR_EVERYONE -> ChatAction.DeleteForEveryone
                }
            )
        }, tag = "message-menu")
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

// Skeletons for the best localized pattern: hour (0-23) or hour (1-12) with minutes.
private val HOUR_24 = charArrayOf('H', 'm').concatToString()
private const val HOUR_12 = "hm"
