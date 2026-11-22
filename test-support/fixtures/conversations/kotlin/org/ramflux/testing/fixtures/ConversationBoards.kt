// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.testing.fixtures

import kotlinx.collections.immutable.persistentListOf
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.conversations.ConversationItem
import org.ramflux.feature.conversations.ConversationsState
import org.ramflux.feature.conversations.impl.R

/**
 * Board content of the module B list boards (MOB-B01 family), shared by the app board captures and
 * the conversations screenshot matrix. Peer names and previews are peer data, identical in every
 * locale; the UI strings come from resources.
 */
object ConversationBoards {
    /** The account shown in the board header ("林澈 · 当前账户"; the model has no suffix). */
    const val ACCOUNT = "林澈"

    /**
     * MOB-B01 rows. The first row is the board's message-requests entry; the model has no such row
     * kind, so it is written as a conversation with the board's title, summary and count. Times are
     * the board's literal labels: the model carries epoch times only and has no relative day labels.
     */
    val rows = persistentListOf(
        ConversationItem("requests", "消息请求", UiText.Data("3 位非联系人的消息"), "3", true),
        ConversationItem("lin-yue", "林悦", UiText.Data("好的，附件里的版本说明也请一起看。"), "1", true, UiText.Data("10:24")),
        ConversationItem("nova", "Nova", UiText.Data("协作设置已确认"), "", false, UiText.Data("09:42")),
        ConversationItem("product", "产品探索", UiText.Data("你：收到，稍后一起看。"), "", false, UiText.Data("10:23")),
        ConversationItem("zhou-ning", "周宁", UiText.Data("好的，明天继续。"), "", false, UiText.Data("昨天")),
        ConversationItem("team", "团队公告", UiText.Data("本周安排与文档"), "5", true, UiText.Data("周二"))
    )

    /** MOB-B01 messages list. */
    val list = ConversationsState(accountLabel = ACCOUNT, rows = rows, loaded = true)

    /** MOB-B01L loading. */
    val loading = ConversationsState(accountLabel = ACCOUNT, loading = true)

    /** MOB-B01X cannot connect: the model's closed-session state, the closest real state. */
    val cannotConnect = ConversationsState(problem = uiText(R.string.ui_private_data_is_closed))

    /** MOB-B12 no conversations yet. */
    val empty = ConversationsState(accountLabel = ACCOUNT, loaded = true)

    /** Board key to state. */
    val boards: Map<String, ConversationsState> = linkedMapOf(
        "MOB-B01" to list,
        "MOB-B01L" to loading,
        "MOB-B01X" to cannotConnect,
        "MOB-B12" to empty
    )
}
