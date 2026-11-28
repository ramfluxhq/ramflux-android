// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.ramflux.data.MlsAdminRules
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsRequestPhase
import org.ramflux.data.MlsScreenEvents
import org.ramflux.data.admin
import org.ramflux.data.approveRequest
import org.ramflux.data.declineRequest
import org.ramflux.data.loadMoreRequests
import org.ramflux.data.loadRequests
import org.ramflux.data.requests

/**
 * The join requests screen. Opening it (or the info of an owner or admin of an approval group) reads page 1; later pages load
 * near the end of the list; a failed page loads again only on "Try again". Approve and Decline each open a confirmation and
 * only its confirm button calls the session (A1). The state lives in the session; closing the screen loses nothing (A8).
 * W: a `@HiltViewModel` constructor.
 */
class MlsRequestsViewModel(private val session: MlsGroupSession) : ViewModel() {
    private class Ids(val account: String, val group: String)

    private val clock: () -> Long = System::currentTimeMillis
    private var current: Ids? = null
    private var requestedFirst = false
    private var lastGone = 0
    private var collector: Job? = null
    private var lockWatch: Job? = null
    private val ask = MutableStateFlow<Pair<MlsRequestConfirmKind, String>?>(null)
    private val noticeSeen = MutableStateFlow(0)
    private val channel = MlsScreenEvents<MlsRequestsEvent>()
    val events = channel.flow
    private val shown = MutableStateFlow(MlsRequestsUi())
    val state: StateFlow<MlsRequestsUi> = shown

    fun onAction(action: MlsRequestsAction) {
        val ids = current
        when (action) {
            is MlsRequestsAction.Open -> open(action.account, action.groupId)
            MlsRequestsAction.LoadMore -> ids?.let { session.loadMoreRequests(it.account, it.group) }
            MlsRequestsAction.Retry -> ids?.let {
                // The first page failed: read it again; a failed later page loads again.
                if (shown.value.loadFailure != null) {
                    session.loadRequests(
                        it.account,
                        it.group
                    )
                } else {
                    session.loadMoreRequests(it.account, it.group)
                }
            }
            is MlsRequestsAction.AskApprove -> askFor(MlsRequestConfirmKind.APPROVE, action.requestId)
            is MlsRequestsAction.AskDecline -> askFor(MlsRequestConfirmKind.DECLINE, action.requestId)
            MlsRequestsAction.Cancel -> ask.value = null
            MlsRequestsAction.DismissNotice -> noticeSeen.value = lastGone
            MlsRequestsAction.Confirm -> confirm(ids)
            MlsRequestsAction.Back -> channel.send(MlsRequestsEvent.Back)
        }
    }

    private fun askFor(kind: MlsRequestConfirmKind, requestId: String) {
        val now = shown.value
        val row = now.rows.firstOrNull { it.requestId == requestId } ?: return
        if (row.phase != MlsRequestPhase.IDLE || row.expired) return
        if (kind == MlsRequestConfirmKind.APPROVE && (now.busy || now.paced)) return
        ask.value = kind to requestId
    }

    private fun confirm(ids: Ids?) {
        val pending = ask.value ?: return
        ask.value = null
        // The "no longer pending" notice belongs to the action it reported: the next action clears it.
        noticeSeen.value = lastGone
        if (ids == null) return
        when (pending.first) {
            MlsRequestConfirmKind.APPROVE -> session.approveRequest(ids.account, ids.group, pending.second)
            MlsRequestConfirmKind.DECLINE -> session.declineRequest(ids.account, ids.group, pending.second)
        }
    }

    private fun open(account: String, group: String) {
        if (current?.account == account && current?.group == group) return
        current = Ids(account, group)
        channel.reopened()
        requestedFirst = false
        session.watch(account)
        session.loadInfo(account, group)
        lockWatch?.cancel()
        lockWatch = dropOnLock(session, account) {
            // The confirmation closes and is dropped, the list is gone with the session state and the screen closes.
            collector?.cancel()
            ask.value = null
            noticeSeen.value = 0
            shown.value = MlsRequestsUi()
            current = null
            channel.lockBack(MlsRequestsEvent.Back)
        }
        collector?.cancel()
        collector = viewModelScope.launch {
            combine(
                combine(
                    session.requests(account, group),
                    session.admin(account, group),
                    session.info(account, group),
                    session.timeline(account, group)
                ) { r, a, i, t ->
                    arrayOf(r, a, i, t)
                },
                ask,
                noticeSeen
            ) { parts, confirm, seen ->
                val requests = parts[0] as org.ramflux.data.MlsRequestsState
                val admin = parts[1] as org.ramflux.data.MlsAdminState
                val info = parts[2] as org.ramflux.data.MlsInfoState
                val timeline = parts[3] as org.ramflux.data.MlsTimelineState
                val shownGroup = info.group
                val eligible = MlsAdminRules.requestsEligible(shownGroup, timeline.readOnly)
                lastGone = requests.goneTicks
                if (eligible && !requestedFirst && !requests.loaded && !requests.loading && requests.loadFailure == null) {
                    requestedFirst = true
                    session.loadRequests(account, group)
                }
                val now = clock()
                val rows = requests.rows.map {
                    MlsRequestRowUi(
                        it.request.requestId,
                        it.request.display,
                        it.request.aiFlag,
                        it.request.requestedAt,
                        it.request.expiresAt,
                        it.phase,
                        it.failure,
                        it.request.expiresAt?.let { at -> at <= now } == true
                    )
                }
                val target = confirm?.let { c -> rows.firstOrNull { it.requestId == c.second } }
                MlsRequestsUi(
                    groupName = shownGroup?.name,
                    rows = rows.toImmutableList(),
                    loading = requests.loading || (shownGroup == null && info.loading),
                    loaded = requests.loaded,
                    loadFailure = requests.loadFailure,
                    pageFailure = requests.pageFailure,
                    loadingMore = requests.loadingMore,
                    hasMore = requests.cursor != null,
                    approvalOff = shownGroup != null && !eligible,
                    busy = admin.busy,
                    paced = admin.pacedUntil?.let { it > now } == true,
                    pacedUntil = admin.pacedUntil,
                    confirm = confirm?.let { c ->
                        target?.let {
                            MlsRequestConfirmUi(
                                c.first,
                                it.requestId,
                                it.display,
                                it.ai,
                                it.expiresAt
                            )
                        }
                    },
                    goneNotice = requests.goneTicks > seen
                )
            }.collect { shown.value = it }
        }
    }
}
