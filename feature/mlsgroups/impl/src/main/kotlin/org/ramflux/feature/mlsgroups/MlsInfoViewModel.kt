// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsInfoState
import org.ramflux.data.MlsReadOnly
import org.ramflux.data.MlsTimelineState

/** The info page: counts, the host row, approval and push (read-only), the entries and Leave. W: a `@HiltViewModel` constructor. */
class MlsInfoViewModel(private val session: MlsGroupSession) : ViewModel() {
    private class Ids(val account: String, val group: String)

    private val confirm = MutableStateFlow(false)
    private val channel = Channel<MlsInfoEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var current: Ids? = null

    private val remote = MutableStateFlow(MlsInfoUi())
    val state: StateFlow<MlsInfoUi> = combine(remote, confirm) { info, ask -> info.copy(confirmLeave = ask) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), MlsInfoUi())

    fun onAction(action: MlsInfoAction) {
        val target = current
        when (action) {
            is MlsInfoAction.Open -> open(action.account, action.groupId)
            MlsInfoAction.Refresh -> target?.let { session.loadInfo(it.account, it.group) }
            MlsInfoAction.AskLeave -> if (target != null) confirm.value = true
            MlsInfoAction.CancelLeave -> confirm.value = false
            MlsInfoAction.ConfirmLeave -> {
                confirm.value = false
                target?.let { session.leaveGroup(it.account, it.group) }
            }
            MlsInfoAction.Invites -> target?.let { channel.trySend(MlsInfoEvent.Invites(it.account, it.group)) }
            MlsInfoAction.Members -> target?.let { channel.trySend(MlsInfoEvent.Members(it.account, it.group)) }
            MlsInfoAction.Back -> channel.trySend(MlsInfoEvent.Back)
        }
    }

    private fun open(account: String, group: String) {
        if (current?.account == account && current?.group == group) return
        current = Ids(account, group)
        session.watch(account)
        session.loadInfo(account, group)
        viewModelScope.launch {
            combine(session.info(account, group), session.timeline(account, group)) { info, timeline -> info to timeline }
                .collect { (info, timeline) -> remote.value = map(info, timeline) }
        }
    }

    private fun map(info: MlsInfoState, timeline: MlsTimelineState): MlsInfoUi {
        val group = info.group
        return MlsInfoUi(
            name = group?.name,
            members = group?.members ?: 0,
            agents = group?.agents ?: 0,
            approval = group?.approval ?: false,
            push = group?.push ?: org.ramflux.data.MlsGroupPush.UNKNOWN,
            hostNode = group?.hostNode,
            hostIpVisible = group?.hostIpVisible ?: false,
            loading = info.loading,
            failure = info.failure,
            leaving = info.leaving,
            leaveFailure = info.leaveFailure,
            left = timeline.readOnly == MlsReadOnly.LEFT,
            readOnly = timeline.readOnly != null,
            owner = group?.myRole == MlsGroupRole.OWNER
        )
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
