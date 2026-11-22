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

    /** The active account id the screen needs for its header actions. */
    private const val ACCOUNT_ID = "account"

    /**
     * MOB-B01 rows. The message-requests entry is the state's request count, not a row. Times are the
     * board's literal labels: the model carries epoch times only and has no relative day labels.
     */
    val rows = persistentListOf(
        ConversationItem("lin-yue", "林悦", UiText.Data("好的，附件里的版本说明也请一起看。"), "1", true, UiText.Data("10:24")),
        ConversationItem("nova", "Nova", UiText.Data("协作设置已确认"), "", false, UiText.Data("09:42")),
        ConversationItem("product", "产品探索", UiText.Data("你：收到，稍后一起看。"), "", false, UiText.Data("10:23")),
        ConversationItem("zhou-ning", "周宁", UiText.Data("好的，明天继续。"), "", false, UiText.Data("昨天")),
        ConversationItem("team", "团队公告", UiText.Data("本周安排与文档"), "5", true, UiText.Data("周二"), muted = true, group = true)
    )

    /** MOB-B01 messages list. */
    val list = ConversationsState(accountId = ACCOUNT_ID, accountLabel = ACCOUNT, rows = rows, loaded = true, requests = 3)

    /** MOB-B01L loading. */
    val loading = ConversationsState(accountId = ACCOUNT_ID, accountLabel = ACCOUNT, loading = true)

    /** MOB-B01X cannot connect: a first read that failed (the closed-session text is the closest real reason). */
    val cannotConnect = ConversationsState(accountId = ACCOUNT_ID, accountLabel = ACCOUNT, problem = uiText(R.string.ui_private_data_is_closed))

    /** MOB-B12 no conversations yet. */
    val empty = ConversationsState(accountId = ACCOUNT_ID, accountLabel = ACCOUNT, loaded = true, requests = 0)

    /** Board key to state. */
    val boards: Map<String, ConversationsState> = linkedMapOf(
        "MOB-B01" to list,
        "MOB-B01L" to loading,
        "MOB-B01X" to cannotConnect,
        "MOB-B12" to empty
    )
}
