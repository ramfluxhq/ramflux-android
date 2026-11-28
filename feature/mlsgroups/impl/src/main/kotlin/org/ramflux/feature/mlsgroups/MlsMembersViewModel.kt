// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.ramflux.data.MlsGroupSession

/**
 * The member list. Pages come from the session one at a time; a failed page shows the error row and loads again only on the
 * explicit "Try again" tap. A search filters the loaded rows and starts loading the remaining pages once. The AI marker is the
 * SDK's `ai_flag` merged in the session (S8); a missing display name is never replaced by the anchor. W: a `@HiltViewModel`
 * constructor.
 */
class MlsMembersViewModel(private val session: MlsGroupSession) : ViewModel() {
    private var account: String? = null
    private var group: String? = null
    private val query = MutableStateFlow("")
    private val channel = Channel<MlsMembersEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val shown = MutableStateFlow(MlsMembersUi())
    val state: StateFlow<MlsMembersUi> = shown

    fun onAction(action: MlsMembersAction) {
        val a = account
        val g = group
        when (action) {
            is MlsMembersAction.Open -> open(action.account, action.groupId)
            MlsMembersAction.LoadMore, MlsMembersAction.Retry -> if (a != null && g != null) session.loadMoreMembers(a, g)
            is MlsMembersAction.Search -> {
                query.value = action.query
                if (action.query.isNotBlank() && a != null && g != null && shown.value.hasMore) session.loadAllMembers(a, g)
            }
            MlsMembersAction.Back -> channel.trySend(MlsMembersEvent.Back)
        }
    }

    private fun open(account: String, group: String) {
        if (this.account == account && this.group == group) return
        this.account = account
        this.group = group
        session.watch(account)
        session.loadMembers(account, group)
        session.loadInfo(account, group)
        viewModelScope.launch {
            combine(session.members(account, group), session.info(account, group), query) { members, info, text ->
                val needle = text.trim().lowercase()
                val rows = members.rows.filter { needle.isEmpty() || it.display?.lowercase()?.contains(needle) == true }
                MlsMembersUi(
                    rows = rows.map { MlsMemberUi(it.anchor, it.display, it.role, it.aiFlag, it.devices, it.isMe) }.toImmutableList(),
                    loading = members.loading,
                    loaded = members.loaded,
                    hasMore = members.cursor != null,
                    failure = members.failure,
                    query = text,
                    searchProgress = if (needle.isNotEmpty() && members.loading && members.cursor != null) {
                        members.rows.size to (info.group?.members ?: 0L)
                    } else {
                        null
                    }
                )
            }.collect { shown.value = it }
        }
    }
}
