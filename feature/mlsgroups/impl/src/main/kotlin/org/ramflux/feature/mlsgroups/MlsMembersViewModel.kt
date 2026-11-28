// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.ramflux.data.MlsAdminKind
import org.ramflux.data.MlsGroupRole
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.admin
import org.ramflux.data.banMember
import org.ramflux.data.clearAdminFailure
import org.ramflux.data.removeMember
import org.ramflux.data.setRole

/**
 * The member list. Pages come from the session one at a time; a failed page shows the error row and loads again only on the
 * explicit "Try again" tap. A search filters the loaded rows and starts loading the remaining pages once. The AI marker is the
 * SDK's `ai_flag` merged in the session (S8); a missing display name is never replaced by the anchor. The row menu follows the
 * role of the latest info (3.3): the owner changes roles and removes or bans; an admin removes or bans plain members; nobody
 * acts on the owner row, the own row or a read-only group. Each action opens a confirmation naming the member, and only its
 * confirm button calls the session (A1). W: a `@HiltViewModel` constructor.
 */
class MlsMembersViewModel(private val session: MlsGroupSession) : ViewModel() {
    private var account: String? = null
    private var group: String? = null
    private var collector: Job? = null
    private var lockWatch: Job? = null
    private val query = MutableStateFlow("")
    private val menu = MutableStateFlow<String?>(null)
    private val ask = MutableStateFlow<Pair<MlsMemberActionKind, String>?>(null)
    private val clock: () -> Long = System::currentTimeMillis
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
            is MlsMembersAction.OpenMenu -> {
                if (a != null && g != null) session.clearAdminFailure(a, g)
                menu.value = action.anchor
            }
            MlsMembersAction.CloseMenu -> menu.value = null
            is MlsMembersAction.Choose -> {
                val anchor = menu.value
                menu.value = null
                val row = shown.value.rows.firstOrNull { it.anchor == anchor }
                // The entry must be one the row offers and the group must be free; the confirmation comes next.
                if (anchor != null && row != null && action.kind in row.actions && !shown.value.busy && !shown.value.paced) {
                    ask.value = action.kind to anchor
                }
            }
            MlsMembersAction.CancelConfirm -> ask.value = null
            MlsMembersAction.Confirm -> {
                val pending = ask.value
                ask.value = null
                if (pending != null && a != null && g != null) {
                    when (pending.first) {
                        MlsMemberActionKind.MAKE_ADMIN -> session.setRole(a, g, pending.second, MlsGroupRole.ADMIN)
                        MlsMemberActionKind.MAKE_MEMBER -> session.setRole(a, g, pending.second, MlsGroupRole.MEMBER)
                        MlsMemberActionKind.REMOVE -> session.removeMember(a, g, pending.second)
                        MlsMemberActionKind.BAN -> session.banMember(a, g, pending.second)
                    }
                }
            }
            MlsMembersAction.Back -> channel.trySend(MlsMembersEvent.Back)
        }
    }

    private class Inputs(
        val admin: org.ramflux.data.MlsAdminState,
        val timeline: org.ramflux.data.MlsTimelineState,
        val menu: String?,
        val ask: Pair<MlsMemberActionKind, String>?
    )

    private fun actionsFor(myRole: MlsGroupRole?, readOnly: Boolean, row: org.ramflux.data.MlsGroupMember): List<MlsMemberActionKind> =
        when {
            readOnly || row.isMe || row.role == MlsGroupRole.OWNER -> emptyList()
            myRole == MlsGroupRole.OWNER -> listOf(
                if (row.role == MlsGroupRole.ADMIN) MlsMemberActionKind.MAKE_MEMBER else MlsMemberActionKind.MAKE_ADMIN,
                MlsMemberActionKind.REMOVE,
                MlsMemberActionKind.BAN
            )
            myRole == MlsGroupRole.ADMIN && row.role != MlsGroupRole.ADMIN -> listOf(MlsMemberActionKind.REMOVE, MlsMemberActionKind.BAN)
            else -> emptyList()
        }

    private fun open(account: String, group: String) {
        if (this.account == account && this.group == group) return
        this.account = account
        this.group = group
        session.watch(account)
        session.loadMembers(account, group)
        session.loadInfo(account, group)
        lockWatch?.cancel()
        lockWatch = dropOnLock(session, account) {
            // The action menu, the confirmation and the search close and are dropped; the page closes.
            collector?.cancel()
            menu.value = null
            ask.value = null
            query.value = ""
            shown.value = MlsMembersUi()
            this.account = null
            this.group = null
            channel.trySend(MlsMembersEvent.Back)
        }
        collector?.cancel()
        collector = viewModelScope.launch {
            combine(
                combine(session.members(account, group), session.info(account, group), query) { m, i, q -> Triple(m, i, q) },
                combine(session.admin(account, group), session.timeline(account, group), menu, ask) { a, t, m, k -> Inputs(a, t, m, k) }
            ) { (members, info, text), rest ->
                val admin = rest.admin
                val timeline = rest.timeline
                val menuAnchor = rest.menu
                val asked = rest.ask
                val needle = text.trim().lowercase()
                val readOnly = timeline.readOnly != null
                val myRole = info.group?.myRole
                val now = clock()
                val busyTarget = (admin.slot ?: admin.pending?.action)?.takeIf {
                    it.kind == MlsAdminKind.ROLE || it.kind == MlsAdminKind.REMOVE || it.kind == MlsAdminKind.BAN
                }?.target
                val rows = members.rows.filter { needle.isEmpty() || it.display?.lowercase()?.contains(needle) == true }
                    .map {
                        MlsMemberUi(
                            it.anchor,
                            it.display,
                            it.role,
                            it.aiFlag,
                            it.devices,
                            it.isMe,
                            actionsFor(myRole, readOnly, it),
                            busyTarget == it.anchor
                        )
                    }
                val target = asked?.let { k -> members.rows.firstOrNull { it.anchor == k.second } }
                MlsMembersUi(
                    rows = rows.toImmutableList(),
                    loading = members.loading,
                    loaded = members.loaded,
                    hasMore = members.cursor != null,
                    failure = members.failure,
                    query = text,
                    searchProgress = if (needle.isNotEmpty() && members.loading && members.cursor != null) {
                        members.rows.size to (info.group?.members ?: 0L)
                    } else {
                        null
                    },
                    groupName = info.group?.name,
                    busy = admin.busy,
                    paced = admin.pacedUntil?.let { it > now } == true,
                    pacedUntil = admin.pacedUntil,
                    menuAnchor = menuAnchor?.takeIf { a -> rows.any { it.anchor == a && it.actions.isNotEmpty() } },
                    confirm = asked?.let { k -> target?.let { MlsMemberConfirmUi(k.first, it.anchor, it.display, it.aiFlag) } },
                    actionFailure = admin.lastFailure?.takeIf {
                        it.kind == MlsAdminKind.ROLE || it.kind == MlsAdminKind.REMOVE || it.kind == MlsAdminKind.BAN
                    }?.failure,
                    details = info.group?.let { MlsDetailsUi(it.hostNode, it.tier, it.lastLogSeq) }
                )
            }.collect { shown.value = it }
        }
    }
}
