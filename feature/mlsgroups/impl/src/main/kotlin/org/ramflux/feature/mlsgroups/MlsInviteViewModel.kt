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
import org.ramflux.data.MlsGroupInvite
import org.ramflux.data.MlsGroupNotices
import org.ramflux.data.MlsGroupSession
import org.ramflux.designsystem.kit.InvitationQr

/**
 * The invite panel (any member may create invites, r4 item 9; list and revoke cover this account's own invites). The link is
 * held in the state and in the QR modules in memory only; both go with the view model. W: a `@HiltViewModel` constructor.
 */
class MlsInviteViewModel(
    private val session: MlsGroupSession,
    private val notices: MlsGroupNotices,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {
    private class Local(
        val days: Int = MlsInviteUi.DEFAULT_DAYS,
        val uses: Int = MlsInviteUi.DEFAULT_USES,
        val confirm: String? = null,
        val noticePending: Boolean = false
    )

    private var account: String? = null
    private var group: String? = null
    private val local = MutableStateFlow(Local())
    private val channel = Channel<MlsInviteEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val shown = MutableStateFlow(MlsInviteUi())
    val state: StateFlow<MlsInviteUi> = shown

    private var qr: InvitationQr? = null
    private var qrLink: String? = null

    /** Lends the QR modules to the drawing frame; nothing is copied (the Kit contract). */
    fun drawQr(block: ((Int, Int) -> Boolean) -> Unit) {
        qr?.lend(block)
    }

    fun onAction(action: MlsInviteAction) {
        val a = account
        val g = group
        when (action) {
            is MlsInviteAction.Open -> open(action)
            is MlsInviteAction.Days -> local.value = copy(days = action.days)
            is MlsInviteAction.Uses -> local.value = copy(uses = action.uses)
            MlsInviteAction.New -> if (a != null && g != null) {
                session.createInvite(a, g, local.value.uses, local.value.days * SECS_PER_DAY)
            }
            is MlsInviteAction.AskRevoke -> local.value = copy(confirm = action.inviteId)
            MlsInviteAction.CancelRevoke -> local.value = copy(confirm = null)
            MlsInviteAction.ConfirmRevoke -> {
                val id = local.value.confirm
                local.value = copy(confirm = null)
                if (a != null && g != null && id != null) session.revokeInvite(a, g, id)
            }
            MlsInviteAction.NoticeSeen -> {
                if (a != null && g != null) notices.markHostIpSeen(a, g)
                local.value = copy(noticePending = false)
            }
            MlsInviteAction.Back -> channel.trySend(MlsInviteEvent.Back)
        }
    }

    private fun copy(days: Int? = null, uses: Int? = null, confirm: String? = local.value.confirm, noticePending: Boolean? = null): Local =
        local.value.let { Local(days ?: it.days, uses ?: it.uses, confirm, noticePending ?: it.noticePending) }

    private fun open(action: MlsInviteAction.Open) {
        if (account == action.account && group == action.groupId) return
        account = action.account
        group = action.groupId
        local.value = Local(noticePending = action.fromCreate && !notices.hostIpSeen(action.account, action.groupId))
        session.watch(action.account)
        session.loadInvites(action.account, action.groupId)
        viewModelScope.launch {
            combine(
                session.invites(action.account, action.groupId),
                session.info(action.account, action.groupId),
                local
            ) { invites, info, local ->
                Triple(invites, info, local)
            }.collect { (invites, info, local) ->
                val now = clock()
                val live = invites.invites.firstOrNull { !it.revoked && !expired(it, now) }
                val link: MlsGroupInvite? = invites.fresh ?: live
                ensureQr(link?.link)
                shown.value = MlsInviteUi(
                    link = link?.link,
                    linkUsesLeft = link?.usesLeft,
                    linkMaxUses = link?.maxUses,
                    linkExpiresAt = link?.expiresAt,
                    qrModules = qr?.modules ?: 0,
                    rows = invites.invites.map {
                        MlsInviteRowUi(
                            it.inviteId,
                            it.maxUses,
                            it.usesLeft,
                            it.expiresAt,
                            it.revoked,
                            expired(it, now),
                            it.inviteId in invites.revoking
                        )
                    }.toImmutableList(),
                    ttlDays = local.days,
                    maxUses = local.uses,
                    loading = invites.loading,
                    creating = invites.creating,
                    confirmRevoke = local.confirm,
                    failure = invites.failure,
                    hostNotice = if (local.noticePending && info.group != null) info.group?.hostNode.orEmpty() else null
                )
            }
        }
    }

    private fun expired(invite: MlsGroupInvite, now: Long) = invite.expiresAt?.let { it <= now } == true

    private fun ensureQr(link: String?) {
        if (link == qrLink) return
        qr?.close()
        qr = link?.let { InvitationQr.encode(it.toCharArray()) }
        qrLink = link
    }

    override fun onCleared() {
        qr?.close()
        qr = null
        qrLink = null
        shown.value = MlsInviteUi()
    }

    private companion object {
        const val SECS_PER_DAY = 86_400L
    }
}
