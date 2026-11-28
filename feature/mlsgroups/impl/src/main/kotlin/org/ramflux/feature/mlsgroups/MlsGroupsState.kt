// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsGroupPendingReason
import org.ramflux.data.MlsGroupRefusal

/** One row of the groups list. Names and snippets are peer data and are always drawn through the untrusted text primitive. */
@Immutable
data class MlsGroupRowUi(
    val id: String,
    val name: String,
    val members: Long,
    val agents: Long,
    val lastActivityAt: Long?,
    val unread: Long,
    val unreadMentions: Long,
    val snippet: String?,
    val catchUp: Boolean
) {
    override fun toString() = "MlsGroupRowUi(<redacted>)"
}

/** A join request that waits for approval. */
@Immutable
data class MlsPendingRowUi(val requestId: String, val reason: MlsGroupPendingReason, val expiresAt: Long?) {
    override fun toString() = "MlsPendingRowUi(<redacted>)"
}

/** What the join sheet shows for its own attempt. */
sealed interface MlsJoinResultUi {
    data class Joined(val groupId: String) : MlsJoinResultUi {
        override fun toString() = "Joined(<redacted>)"
    }

    data class Pending(val reason: MlsGroupPendingReason, val expiresAt: Long?) : MlsJoinResultUi

    /** [groupId] is the group "Open group" navigates to; the groups list when it is null. */
    data class Refused(
        val reason: MlsGroupRefusal,
        val expiredAt: Long?,
        val retryAfterSecs: Long?,
        val groupId: String?
    ) : MlsJoinResultUi {
        override fun toString() = "Refused(<redacted>)"
    }

    data class Failed(val failure: MlsFailure) : MlsJoinResultUi
}

@Immutable
data class MlsJoinSheetUi(
    val link: String = "",
    val joining: Boolean = false,
    val result: MlsJoinResultUi? = null
) {
    override fun toString() = "MlsJoinSheetUi(<redacted>)"
}

@Immutable
data class MlsGroupsState(
    val rows: ImmutableList<MlsGroupRowUi> = persistentListOf(),
    val pending: ImmutableList<MlsPendingRowUi> = persistentListOf(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val failure: MlsFailure? = null,
    /** The join sheet, while it is open. */
    val join: MlsJoinSheetUi? = null
) {
    override fun toString() = "MlsGroupsState(<redacted>)"
}

sealed interface MlsGroupsAction {
    data class Open(val account: String) : MlsGroupsAction
    data object Refresh : MlsGroupsAction
    data object LoadMore : MlsGroupsAction
    data class OpenGroup(val groupId: String) : MlsGroupsAction
    data object NewGroup : MlsGroupsAction
    data object OpenJoin : MlsGroupsAction
    data object CloseJoin : MlsGroupsAction
    data class Link(val text: String) : MlsGroupsAction {
        override fun toString() = "Link(<redacted>)"
    }

    /** The text of the clipboard, read on the Paste tap only. */
    data class Paste(val text: String) : MlsGroupsAction {
        override fun toString() = "Paste(<redacted>)"
    }

    data object Submit : MlsGroupsAction

    /** "Open group" of a join outcome. */
    data class OpenJoined(val groupId: String?) : MlsGroupsAction
    data object Back : MlsGroupsAction
}

sealed interface MlsGroupsEvent {
    data class OpenGroup(val account: String, val groupId: String) : MlsGroupsEvent
    data object NewGroup : MlsGroupsEvent
    data object Back : MlsGroupsEvent
}

// ---- create ----------------------------------------------------------------------------------------------------------

@Immutable
data class MlsCreateUi(
    val name: String = "",
    val approval: Boolean = true,
    val creating: Boolean = false,
    val nameError: org.ramflux.data.MlsNameError? = null,
    val failure: MlsFailure? = null
) {
    override fun toString() = "MlsCreateUi(<redacted>)"
}

sealed interface MlsCreateAction {
    data class Open(val account: String) : MlsCreateAction
    data class Name(val text: String) : MlsCreateAction
    data class Approval(val on: Boolean) : MlsCreateAction
    data object Submit : MlsCreateAction
    data object Back : MlsCreateAction
}

sealed interface MlsCreateEvent {
    /** The group exists: open it, then its invite panel. */
    data class Created(val account: String, val groupId: String) : MlsCreateEvent
    data object Back : MlsCreateEvent
}

// ---- invites ---------------------------------------------------------------------------------------------------------

@Immutable
data class MlsInviteRowUi(
    val id: String,
    val maxUses: Long?,
    val usesLeft: Long?,
    val expiresAt: Long?,
    val revoked: Boolean,
    val expired: Boolean,
    val revoking: Boolean
) {
    override fun toString() = "MlsInviteRowUi(<redacted>)"
}

@Immutable
data class MlsInviteUi(
    /** The link of the panel: the invite just created, else the newest usable one. Held in memory only (S1). */
    val link: String? = null,
    val linkUsesLeft: Long? = null,
    val linkMaxUses: Long? = null,
    val linkExpiresAt: Long? = null,
    val qrModules: Int = 0,
    val rows: ImmutableList<MlsInviteRowUi> = persistentListOf(),
    val ttlDays: Int = DEFAULT_DAYS,
    val maxUses: Int = DEFAULT_USES,
    /** The approval setting once the group is read; the secret note follows it, and there is no note before it is known. */
    val approval: Boolean? = null,
    val loading: Boolean = false,
    val creating: Boolean = false,
    val confirmRevoke: String? = null,
    val failure: MlsFailure? = null,
    /** The one-time `host_ip_visible` notice, after the panel of a new group. */
    val hostNotice: String? = null
) {
    override fun toString() = "MlsInviteUi(<redacted>)"

    companion object {
        const val DEFAULT_DAYS = 7
        const val DEFAULT_USES = 100
        val DAYS = listOf(1, 7)
        val USES = listOf(1, 10, 100)
    }
}

sealed interface MlsInviteAction {
    data class Open(val account: String, val groupId: String, val fromCreate: Boolean) : MlsInviteAction
    data class Days(val days: Int) : MlsInviteAction
    data class Uses(val uses: Int) : MlsInviteAction
    data object New : MlsInviteAction
    data class AskRevoke(val inviteId: String) : MlsInviteAction
    data object CancelRevoke : MlsInviteAction
    data object ConfirmRevoke : MlsInviteAction
    data object NoticeSeen : MlsInviteAction
    data object Back : MlsInviteAction
}

sealed interface MlsInviteEvent {
    data object Back : MlsInviteEvent
}

// ---- info ------------------------------------------------------------------------------------------------------------

@Immutable
data class MlsInfoUi(
    /** The group has been read: until then the page shows a neutral loading state and no approval, push or count row. */
    val known: Boolean = false,
    val name: String? = null,
    val members: Long = 0,
    val agents: Long = 0,
    val approval: Boolean = false,
    val push: org.ramflux.data.MlsGroupPush = org.ramflux.data.MlsGroupPush.UNKNOWN,
    val hostNode: String? = null,
    val hostIpVisible: Boolean = false,
    val canInvite: Boolean = true,
    val loading: Boolean = false,
    val failure: MlsFailure? = null,
    val confirmLeave: Boolean = false,
    val leaving: Boolean = false,
    val leaveFailure: MlsFailure? = null,
    val left: Boolean = false,
    /** Left, removed or banned: no invite can be made or revoked any more. */
    val readOnly: Boolean = false,
    val owner: Boolean = false,
    val admin: MlsInfoAdminUi = MlsInfoAdminUi()
) {
    override fun toString() = "MlsInfoUi(<redacted>)"
}

sealed interface MlsInfoAction {
    data class Open(val account: String, val groupId: String) : MlsInfoAction
    data object Refresh : MlsInfoAction
    data object AskLeave : MlsInfoAction
    data object CancelLeave : MlsInfoAction
    data object ConfirmLeave : MlsInfoAction
    data object Invites : MlsInfoAction
    data object Requests : MlsInfoAction
    data object Settings : MlsInfoAction
    data class Push(val push: org.ramflux.data.MlsGroupPush) : MlsInfoAction
    data object Members : MlsInfoAction
    data object Back : MlsInfoAction
}

sealed interface MlsInfoEvent {
    data class Invites(val account: String, val groupId: String) : MlsInfoEvent
    data class Members(val account: String, val groupId: String) : MlsInfoEvent
    data class Requests(val account: String, val groupId: String) : MlsInfoEvent
    data class Settings(val account: String, val groupId: String) : MlsInfoEvent
    data object Back : MlsInfoEvent
}

// ---- members ---------------------------------------------------------------------------------------------------------

@Immutable
data class MlsMemberUi(
    val anchor: String,
    val display: String?,
    val role: org.ramflux.data.MlsGroupRole,
    val ai: Boolean,
    val devices: Long,
    val me: Boolean,
    /** The actions the signed-in role may take on this row (3.3); empty for the owner row, the own row and read-only groups. */
    val actions: List<MlsMemberActionKind> = emptyList(),
    /** An admin action on this member is running or waits for its event: "Updating…". */
    val updating: Boolean = false
) {
    override fun toString() = "MlsMemberUi(<redacted>)"
}

@Immutable
data class MlsMembersUi(
    val rows: ImmutableList<MlsMemberUi> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val failure: MlsFailure? = null,
    val query: String = "",
    /** While a search loads the remaining pages: loaded rows and the group's member count. */
    val searchProgress: Pair<Int, Long>? = null,
    val groupName: String? = null,
    /** Another admin commit is running or waits for its event: every entry is disabled with the busy text. */
    val busy: Boolean = false,
    val paced: Boolean = false,
    val pacedUntil: Long? = null,
    /** The anchor whose action menu is open. */
    val menuAnchor: String? = null,
    val confirm: MlsMemberConfirmUi? = null,
    /** The typed refusal of the last member action, until the next one starts. */
    val actionFailure: MlsFailure? = null,
    /** The group evidence under the collapsed "Technical details" at the bottom of the list; null until the group is read. */
    val details: MlsDetailsUi? = null
) {
    override fun toString() = "MlsMembersUi(<redacted>)"
}

sealed interface MlsMembersAction {
    data class Open(val account: String, val groupId: String) : MlsMembersAction
    data object LoadMore : MlsMembersAction
    data object Retry : MlsMembersAction
    data class Search(val query: String) : MlsMembersAction
    data class OpenMenu(val anchor: String) : MlsMembersAction
    data object CloseMenu : MlsMembersAction
    data class Choose(val kind: MlsMemberActionKind) : MlsMembersAction
    data object Confirm : MlsMembersAction
    data object CancelConfirm : MlsMembersAction
    data object Back : MlsMembersAction
}

sealed interface MlsMembersEvent {
    data object Back : MlsMembersEvent
}
