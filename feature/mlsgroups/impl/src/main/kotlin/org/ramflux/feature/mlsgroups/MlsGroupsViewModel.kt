// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsGroupListState
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsJoinOutcome
import org.ramflux.data.MlsJoinState

/**
 * The groups list and its join sheet. It owns no call: every call runs in the process-wide [MlsGroupSession], so a result is
 * recorded when this screen is gone. The join field is plain view state: it is never saved, restored or logged (S1), and it is
 * cleared when the sheet closes and after a join.
 * W: a `@HiltViewModel` constructor taking the session and the active account.
 */
class MlsGroupsViewModel(
    private val session: MlsGroupSession,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private class Sheet(val open: Boolean = false, val link: String = "")

    private val account = MutableStateFlow<String?>(null)
    private val sheet = MutableStateFlow(Sheet())
    private var lockWatch: Job? = null
    private val channel = Channel<MlsGroupsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote = account.flatMapLatest { id ->
        if (id == null) flowOf(MlsGroupListState() to MlsJoinState()) else combine(session.groups(id), session.join(id)) { l, j -> l to j }
    }

    val state: StateFlow<MlsGroupsState> = combine(remote, sheet) { (list, join), sheet -> build(list, join, sheet) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), MlsGroupsState())

    init {
        // A joined group clears the field (the result line stays).
        viewModelScope.launch {
            remote.collect { (_, join) ->
                if (join.outcome is MlsJoinOutcome.Joined && sheet.value.link.isNotEmpty()) sheet.value = Sheet(sheet.value.open, "")
            }
        }
    }

    private fun build(list: MlsGroupListState, join: MlsJoinState, sheet: Sheet): MlsGroupsState {
        val now = clock()
        return MlsGroupsState(
            rows = list.rows.map {
                MlsGroupRowUi(
                    it.groupId, it.name, it.members, it.agents, it.lastActivityAt, it.unread, it.unreadMentions,
                    it.lastPost?.snippet, it.catchUp != org.ramflux.data.MlsGroupCatchUp.IDLE
                )
            }.toImmutableList(),
            pending = join.pending.filter { (it.expiresAt ?: Long.MAX_VALUE) > now }
                .map { MlsPendingRowUi(it.requestId, it.reason, it.expiresAt) }.toImmutableList(),
            loading = list.loading,
            loadingMore = list.loadingMore,
            loaded = list.loaded,
            failure = list.failure,
            join = if (sheet.open) {
                MlsJoinSheetUi(sheet.link, join.inFlight, join.outcome?.let(::result))
            } else {
                null
            }
        )
    }

    private fun result(outcome: MlsJoinOutcome): MlsJoinResultUi = when (outcome) {
        is MlsJoinOutcome.Joined -> MlsJoinResultUi.Joined(outcome.groupId)
        is MlsJoinOutcome.Pending -> MlsJoinResultUi.Pending(outcome.record.reason, outcome.record.expiresAt)
        is MlsJoinOutcome.Refused -> MlsJoinResultUi.Refused(
            outcome.refusal.reason,
            outcome.refusal.expiredAt,
            outcome.refusal.retryAfterSecs,
            outcome.refusal.groupId
        )
        is MlsJoinOutcome.Failed -> MlsJoinResultUi.Failed(outcome.failure)
    }

    fun onAction(action: MlsGroupsAction) {
        val id = account.value
        when (action) {
            is MlsGroupsAction.Open -> {
                account.value = action.account
                lockWatch?.cancel()
                // The join sheet and the typed link close and are dropped when the lock engages or the account goes away.
                lockWatch = dropOnLock(session, action.account) { sheet.value = Sheet() }
                session.watch(action.account)
                session.loadGroups(action.account)
            }
            MlsGroupsAction.Refresh -> id?.let(session::loadGroups)
            MlsGroupsAction.LoadMore -> id?.let(session::loadMoreGroups)
            is MlsGroupsAction.OpenGroup -> id?.let { channel.trySend(MlsGroupsEvent.OpenGroup(it, action.groupId)) }
            MlsGroupsAction.NewGroup -> channel.trySend(MlsGroupsEvent.NewGroup)
            MlsGroupsAction.OpenJoin -> {
                sheet.value = Sheet(open = true)
                id?.let(session::joinSheetChanged)
            }
            MlsGroupsAction.CloseJoin -> {
                sheet.value = Sheet()
                id?.let(session::joinSheetChanged)
            }
            is MlsGroupsAction.Link -> if (sheet.value.open) sheet.value = Sheet(true, action.text)
            is MlsGroupsAction.Paste -> if (sheet.value.open) sheet.value = Sheet(true, action.text)
            MlsGroupsAction.Submit -> {
                val current = sheet.value
                // The button is disabled in flight; the session ignores a second tap as well.
                if (current.open && id != null) session.submitJoin(id, current.link)
            }
            is MlsGroupsAction.OpenJoined -> {
                sheet.value = Sheet()
                id?.let(session::joinSheetChanged)
                if (id != null && action.groupId != null) channel.trySend(MlsGroupsEvent.OpenGroup(id, action.groupId))
            }
            MlsGroupsAction.Back -> channel.trySend(MlsGroupsEvent.Back)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
