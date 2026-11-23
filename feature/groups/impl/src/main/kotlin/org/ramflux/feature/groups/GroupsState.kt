// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.designsystem.UiText

/** MEMBER is one member's roles page; INVITE picks an accepted contact to add. */
enum class GroupsPage { LIST, CREATE, DETAIL, MEMBERS, MEMBER, INVITE, REQUESTS, INVITATION }

/** The windowed lists of the groups screen; each reads through its own repository pages. */
enum class GroupList { GROUPS, INVITATIONS, MEMBERS, CONTACTS, REQUESTS }

/** Controls the SDK cannot perform from a phone today; tapping one explains why instead of acting. */
enum class GroupControl { TRANSFER, LEAVE }

@Immutable data class GroupRow(val id: String, val title: String, val count: UiText)

@Immutable data class MemberRow(
    val id: String,
    val principal: String,
    val root: String?,
    val device: String,
    val role: UiText,
    val active: Boolean,
    val manageable: Boolean,
    val admin: Boolean = false
)

@Immutable data class InviteRow(val id: String, val title: String, val creator: String, val inviter: String, val expiry: UiText)

@Immutable data class RequestRow(val id: String, val status: UiText, val expiry: UiText)

@Immutable data class GroupFacts(val operation: UiText, val values: ImmutableList<UiText>)

/** Cursor state of one windowed list: more rows after or before the loaded window. */
@Immutable data class ListPaging(val more: Boolean = false, val before: Boolean = false)

@Immutable data class GroupsState(
    val page: GroupsPage = GroupsPage.LIST,
    val account: String? = null,
    val accountRequired: Boolean = false,
    val groups: ImmutableList<GroupRow> = persistentListOf(),
    val invitations: ImmutableList<InviteRow> = persistentListOf(),
    val members: ImmutableList<MemberRow> = persistentListOf(),
    val contacts: ImmutableList<GroupRow> = persistentListOf(),
    val requests: ImmutableList<RequestRow> = persistentListOf(),
    val paging: ImmutableMap<GroupList, ListPaging> = persistentMapOf(),
    val selected: String? = null,
    val title: String? = null,
    val memberCount: UiText = UiText.Empty,
    val ownRole: UiText? = null,
    val selectedMember: MemberRow? = null,
    val selectedInvitation: InviteRow? = null,
    val epoch: UiText = UiText.Empty,
    val query: String = "",
    val name: String = "",
    val canManage: Boolean = false,
    val authorityFresh: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val problem: UiText? = null,
    val notice: UiText? = null,
    val pending: String? = null,
    val confirmation: GroupFacts? = null
) {
    fun paging(list: GroupList) = paging[list] ?: ListPaging()
    override fun toString() = "GroupsState(<redacted>)"
}
