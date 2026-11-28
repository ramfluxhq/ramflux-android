// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsAdminRules
import org.ramflux.data.MlsAdminState
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsGroupWarning
import org.ramflux.data.MlsInfoState
import org.ramflux.data.MlsReadOnly
import org.ramflux.data.MlsRequestsState
import org.ramflux.data.MlsTimelineState
import org.ramflux.data.admin
import org.ramflux.data.loadRequests
import org.ramflux.data.setPush

/**
 * The info page: counts, the host row, approval, the per-device push picker, the requests and settings entries for an owner or
 * admin, the quota, privacy-delay and warning rows, the entries and Leave. A read-only group shows none of the admin rows (A16).
 * Opening it as an owner or admin of an approval group reads the first page of requests once. W: a `@HiltViewModel` constructor.
 */
class MlsInfoViewModel(private val session: MlsGroupSession) : ViewModel() {
    private class Ids(val account: String, val group: String)

    private val confirm = MutableStateFlow(false)
    private val channel = Channel<MlsInfoEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var current: Ids? = null
    private var requestedFirst = false

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
            MlsInfoAction.Requests -> target?.let { channel.trySend(MlsInfoEvent.Requests(it.account, it.group)) }
            MlsInfoAction.Settings -> target?.let { channel.trySend(MlsInfoEvent.Settings(it.account, it.group)) }
            is MlsInfoAction.Push -> target?.let { session.setPush(it.account, it.group, action.push) }
            MlsInfoAction.Back -> channel.trySend(MlsInfoEvent.Back)
        }
    }

    private fun open(account: String, group: String) {
        if (current?.account == account && current?.group == group) return
        current = Ids(account, group)
        session.watch(account)
        session.loadInfo(account, group)
        viewModelScope.launch {
            combine(
                session.info(account, group),
                session.timeline(account, group),
                session.admin(account, group)
            ) { info, timeline, admin ->
                Triple(info, timeline, admin)
            }.collect { (info, timeline, admin) ->
                val eligible = MlsAdminRules.requestsEligible(info.group, timeline.readOnly)
                if (eligible && !requestedFirst && !admin.requests.loaded && !admin.requests.loading) {
                    requestedFirst = true
                    session.loadRequests(account, group)
                }
                remote.value = map(info, timeline, admin)
            }
        }
    }

    private fun map(info: MlsInfoState, timeline: MlsTimelineState, admin: MlsAdminState): MlsInfoUi {
        val group = info.group
        val readOnly = timeline.readOnly != null
        val isAdmin = group != null && !readOnly && MlsAdminRules.isAdmin(group.myRole)
        val requests: MlsRequestsState = admin.requests
        val warnings = if (readOnly) emptyList<MlsGroupWarning>() else MlsAdminRules.visibleWarnings(admin.warnings, group?.myRole)
        return MlsInfoUi(
            name = group?.name,
            members = group?.members ?: 0,
            agents = group?.agents ?: 0,
            approval = group?.approval ?: false,
            push = group?.push ?: MlsGroupPush.UNKNOWN,
            hostNode = group?.hostNode,
            hostIpVisible = group?.hostIpVisible ?: false,
            loading = info.loading,
            failure = info.failure,
            leaving = info.leaving,
            leaveFailure = info.leaveFailure,
            left = timeline.readOnly == MlsReadOnly.LEFT,
            readOnly = readOnly,
            owner = group?.myRole == MlsGroupRole.OWNER,
            admin = MlsInfoAdminUi(
                requestCount = if (MlsAdminRules.requestsEligible(group, timeline.readOnly)) requests.rows.size else null,
                requestsMore = requests.cursor != null,
                settingsEntry = isAdmin,
                push = if (group != null && !readOnly) MlsPushUi(group.push, admin.pushSaving, admin.pushFailure != null) else null,
                commitQuota = group?.quotaCommit?.takeIf { !readOnly && MlsAdminRules.quotaLineVisible(it) }
                    ?.let { MlsQuotaLineUi(it.remaining, it.limit, it.firstDay, it.resetsAt) },
                privacyDelay = group != null && !readOnly && group.privacyDelayArmed,
                warnings = warnings.toImmutableList(),
                hintActions = group != null && !readOnly
            )
        )
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
