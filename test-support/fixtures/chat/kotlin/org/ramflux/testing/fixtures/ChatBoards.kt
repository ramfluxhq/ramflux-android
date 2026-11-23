// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.testing.fixtures

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.collections.immutable.persistentListOf
import org.ramflux.designsystem.UiText
import org.ramflux.feature.chat.ChatState
import org.ramflux.feature.chat.MessageItem
import org.ramflux.feature.chat.Receipt

/**
 * Board content of the chat boards (MOB-B02, its dark proof MOB-DK01, MOB-B02E and MOB-B03), shared by
 * the app board captures and the chat screenshot matrix. Peer names and message text are peer data,
 * identical in every locale. The boards use the 24-hour clock at 10:30 on the day of the messages.
 */
object ChatBoards {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** "Now" of the boards, so the date chip reads "today". */
    val clock: Clock = Clock.fixed(LocalDateTime.of(2026, 11, 20, 10, 30).atZone(zone).toInstant(), zone)

    /** Epoch seconds of a time on the boards' day, [daysAgo] days earlier. */
    fun at(hour: Int, minute: Int, daysAgo: Long = 0): Long =
        LocalDateTime.of(2026, 11, 20, hour, minute).minusDays(daysAgo).atZone(zone).toEpochSecond()

    private const val PEER = "林悦"

    /** MOB-B02 and MOB-DK01; with [selected], MOB-B03 (the own message's actions open). */
    fun conversation(selected: Boolean = false) = ChatState(
        conversationId = "lin-yue",
        title = UiText.Data(PEER),
        loaded = true,
        canSend = true,
        rows = persistentListOf(
            MessageItem("b1", PEER, false, false, UiText.Data("新版资料我整理好了，你有空时看一下。"), at(10, 21)),
            MessageItem("b2", "Self", true, false, UiText.Data("收到，我会把问题逐项回复。"), at(10, 23), receipt = Receipt.READ),
            MessageItem("b3", PEER, false, false, UiText.Data("好的，附件里的版本说明也请一起看。"), at(10, 24))
        ),
        firstUnreadId = "b3",
        selectedId = if (selected) "b2" else null
    )

    /** MOB-B02E: the conversation exists and has no messages yet. */
    val empty = ChatState(conversationId = "lin-yue", title = UiText.Data(PEER), loaded = true, canSend = true)
}
