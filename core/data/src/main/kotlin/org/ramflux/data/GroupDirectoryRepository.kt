// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.Flow

enum class GroupRole { OWNER, ADMIN, MEMBER, BOT, UNKNOWN }
data class GroupSummary(val id: String, val name: String?, val epoch: ULong, val members: List<String>)
/** [name] and [ai] come from the contact list (display name, SDK-verified AI marker); both are absent for strangers. */
data class GroupMember(val principal: String, val root: String?, val device: String, val role: GroupRole, val active: Boolean,
    val name: String? = null, val ai: Boolean = false) {
    val id get() = root.orEmpty().length.toString() + ":" + root.orEmpty() + device
}
data class GroupAuthority(val principal: String, val root: String, val device: String, val authorized: Boolean)
data class GroupDetail(val group: GroupSummary, val roster: List<GroupMember>, val own: GroupAuthority) {
    val ownRole get() = roster.singleOrNull { it.active && it.root == own.root && it.device == own.device }?.role
    val canManage get() = own.authorized && ownRole in setOf(GroupRole.OWNER, GroupRole.ADMIN)
    /** The owner leaves only after transferring ownership (SDK rule); every other active member can leave. */
    val canLeave get() = own.authorized && ownRole != null && ownRole != GroupRole.OWNER
    fun canChange(member: GroupMember) = canManage && member.active && member.root != null && member.role != GroupRole.OWNER &&
        !(member.root == own.root && member.device == own.device)
}
data class GroupInvitation(val id: String, val name: String?, val creator: String, val inviter: String, val expiresAt: Long)
data class GroupRequest(val id: String, val group: String, val status: String, val waiting: Boolean, val expiresAt: Long)
enum class GroupOperation { CREATE, RENAME, ADD, REMOVE, ROLE, ACCEPT, DECLINE, LEAVE }
data class GroupChange(val operation: GroupOperation, val group: String, val epoch: ULong? = null,
    val name: String = "", val member: GroupMember? = null, val principal: String = "", val root: String = "",
    val role: GroupRole = GroupRole.MEMBER, val invitation: GroupInvitation? = null) {
    override fun toString() = "GroupChange(<redacted>)"
}
/** [conversation] is the conversation the SDK bound to the group when the reply names it (a creation reply does). */
data class GroupOutcome(val group: String, val waiting: Boolean, val expired: Boolean, val request: String?, val conversation: String? = null)
data class PendingGroupOperation(val operationId: String, val change: GroupChange)
interface GroupDirectoryRepository {
    /** Unpaged; for the creation read-back only. Lists read [groupPage]. */
    suspend fun list(account: String): ClientResult<List<GroupSummary>>
    suspend fun detail(account: String, group: String): ClientResult<GroupDetail>
    suspend fun invitations(account: String): ClientResult<List<GroupInvitation>>
    suspend fun requests(account: String, group: String): ClientResult<List<GroupRequest>>
    suspend fun conversation(account: String, group: String): ClientResult<String>
    suspend fun mutate(account: String, change: GroupChange, operationId: String): ClientResult<GroupOutcome>
    fun pending(account: String): PendingGroupOperation?

    /**
     * Window pages over the unpaged SDK lists (`group.list`, `group.members`, `group.invitations.list`,
     * `group.requests.list`). A null cursor takes a fresh snapshot; the page cursors continue it in either
     * direction. [query] filters by name or id (members: principal or device), case-insensitive.
     */
    suspend fun groupPage(account: String, query: String = "", cursor: String? = null): ClientResult<WindowedPage<GroupSummary>> =
        ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)

    suspend fun memberPage(account: String, group: String, query: String = "", cursor: String? = null): ClientResult<WindowedPage<GroupMember>> =
        ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)

    suspend fun invitationPage(account: String, cursor: String? = null): ClientResult<WindowedPage<GroupInvitation>> =
        ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)

    suspend fun requestPage(account: String, group: String, cursor: String? = null): ClientResult<WindowedPage<GroupRequest>> =
        ClientResult.Unavailable(ClientFeature.GROUPS, UnavailableReason.UNSUPPORTED_BY_CORE)
    fun updates(account: String): Flow<ClientResult<Unit>>
}
