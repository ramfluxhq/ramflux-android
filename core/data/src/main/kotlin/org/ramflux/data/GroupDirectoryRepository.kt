// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.Flow

enum class GroupRole { OWNER, ADMIN, MEMBER, BOT, UNKNOWN }
data class GroupSummary(val id: String, val name: String?, val epoch: ULong, val members: List<String>)
data class GroupMember(val principal: String, val root: String?, val device: String, val role: GroupRole, val active: Boolean) {
    val id get() = root.orEmpty().length.toString() + ":" + root.orEmpty() + device
}
data class GroupAuthority(val principal: String, val root: String, val device: String, val authorized: Boolean)
data class GroupDetail(val group: GroupSummary, val roster: List<GroupMember>, val own: GroupAuthority) {
    val ownRole get() = roster.singleOrNull { it.active && it.root == own.root && it.device == own.device }?.role
    val canManage get() = own.authorized && ownRole in setOf(GroupRole.OWNER, GroupRole.ADMIN)
    fun canChange(member: GroupMember) = canManage && member.active && member.root != null && member.role != GroupRole.OWNER &&
        !(member.root == own.root && member.device == own.device)
}
data class GroupInvitation(val id: String, val name: String?, val creator: String, val inviter: String, val expiresAt: Long)
data class GroupRequest(val id: String, val group: String, val status: String, val waiting: Boolean, val expiresAt: Long)
enum class GroupOperation { CREATE, RENAME, ADD, REMOVE, ROLE, ACCEPT, DECLINE }
data class GroupChange(val operation: GroupOperation, val group: String, val epoch: ULong? = null,
    val name: String = "", val member: GroupMember? = null, val principal: String = "", val root: String = "",
    val role: GroupRole = GroupRole.MEMBER, val invitation: GroupInvitation? = null) {
    override fun toString() = "GroupChange(<redacted>)"
}
data class GroupOutcome(val group: String, val waiting: Boolean, val expired: Boolean, val request: String?)
data class PendingGroupOperation(val operationId: String, val change: GroupChange)
interface GroupDirectoryRepository {
    suspend fun list(account: String): ClientResult<List<GroupSummary>>
    suspend fun detail(account: String, group: String): ClientResult<GroupDetail>
    suspend fun invitations(account: String): ClientResult<List<GroupInvitation>>
    suspend fun requests(account: String, group: String): ClientResult<List<GroupRequest>>
    suspend fun conversation(account: String, group: String): ClientResult<String>
    suspend fun mutate(account: String, change: GroupChange, operationId: String): ClientResult<GroupOutcome>
    fun pending(account: String): PendingGroupOperation?
    fun updates(account: String): Flow<ClientResult<Unit>>
}
