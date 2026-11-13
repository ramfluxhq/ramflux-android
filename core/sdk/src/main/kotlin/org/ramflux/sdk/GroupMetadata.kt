// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

data class GroupDisplay(val id: String, val name: String?, val epoch: ULong, val members: List<String>)
data class GroupMemberDisplay(val principal: String, val root: String?, val device: String, val role: String, val active: Boolean)
data class GroupDetailDisplay(val group: GroupDisplay, val roster: List<GroupMemberDisplay>)
data class GroupAuthorityDisplay(val principal: String, val root: String, val device: String, val authorized: Boolean, val account: String)
data class GroupInvitationDisplay(val id: String, val name: String?, val creator: String, val inviter: String, val expires: Long)
data class GroupRequestDisplay(val id: String, val group: String, val status: String, val waiting: Boolean, val expires: Long)
data class GroupOutcomeDisplay(val group: String, val waiting: Boolean, val expired: Boolean, val request: String?)
object GroupMetadataReader {
    fun list(reply: SecretReply) = decode(reply) { f -> f.rows("groups", 512).map { group(f, it) }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) } }
    fun detail(reply: SecretReply) = decode(reply) { f ->
        val group = group(f, f.root)
        val roster = if (f.field(f.root, "member_roster") == null) {
            // Device-only legacy display cannot grant root-qualified controls.
            group.members.map { GroupMemberDisplay("", null, it, "unknown", false) }
        } else {
            val roles = requireNotNull(f.field(f.root, "roles"))
            val excluded = f.rows("excluded_devices", 1024).map { row -> text(f, row, "root_commitment") to text(f, row, "device_id") }.toSet()
            f.rows("member_roster", 1024).map { row ->
                val identity = requireNotNull(f.field(row, "identity"))
                val root = text(f, identity, "root_commitment"); val device = text(f, identity, "device_id"); val role = text(f, row, "role")
                GroupMemberDisplay(text(f, identity, "principal_id"), root, device, role,
                    device in group.members && (root to device) !in excluded && f.text(roles, device) == role)
            }
        }
        require(roster.map { it.root to it.device }.distinct().size == roster.size)
        GroupDetailDisplay(group, roster)
    }
    fun authority(reply: SecretReply) = decode(reply) { f ->
        val authorized = f.boolean(f.root, "local_device_authorized")
        // Missing native authority evidence is display-only, never permission inferred from a device name.
        val blocked = f.field(f.root, "authority_blocked") == null || f.boolean(f.root, "authority_blocked")
        GroupAuthorityDisplay(text(f, f.root, "principal_id"), text(f, f.root, "principal_commitment"), text(f, f.root, "device_id"), authorized && !blocked, text(f, f.root, "local_account_id"))
    }
    fun invitations(reply: SecretReply) = decode(reply) { f -> f.rows("invitations", 512).map { row ->
        GroupInvitationDisplay(text(f, row, "group_id"), f.text(row, "name"), text(f, row, "creator"), text(f, row, "inviter"), requireNotNull(f.optionalSigned(row, "expires_at")))
    }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) } }
    fun requests(reply: SecretReply) = decode(reply) { f -> f.rows("requests", 512).map { row ->
        GroupRequestDisplay(text(f, row, "request_id"), text(f, row, "group_id"), text(f, row, "status"), f.boolean(row, "waiting_for_owner"), requireNotNull(f.optionalSigned(row, "expires_at")))
    }.also { rows -> require(rows.map { it.id }.distinct().size == rows.size) } }
    fun conversation(reply: SecretReply, expected: String) = decode(reply) { f ->
        require(text(f, f.root, "group_id") == expected)
        text(f, f.root, "conversation_id")
    }
    fun outcome(reply: SecretReply, expected: String, invitationAccept: Boolean?) = decode(reply) { f ->
        require(text(f, f.root, "group_id") == expected)
        val accepted = f.boolean(f.root, "accepted")
        val waiting = f.field(f.root, "pending")?.let { f.boolean(f.root, "pending") } ?: false
        val expired = !accepted && !waiting && f.text(f.root, "status") == "expired"
        val request = f.text(f.root, "request_id")
        if (expired || waiting) require(!request.isNullOrBlank())
        else if (invitationAccept != null) {
            require(accepted == invitationAccept && f.boolean(f.root, "declined") == !invitationAccept)
        } else require(accepted)
        GroupOutcomeDisplay(expected, waiting, expired, request)
    }
    private fun group(f: DisplayFields, row: ByteJson.Node): GroupDisplay {
        val members = f.texts(row, "members", 1024, 0, 4096)
        require(members.distinct().size == members.size)
        return GroupDisplay(text(f, row, "group_id"), f.text(row, "name"), f.unsigned(row, "group_epoch"), members)
    }
    private fun text(f: DisplayFields, row: ByteJson.Node, key: String) = requireNotNull(f.text(row, key, true))
    private fun <T> decode(reply: SecretReply, body: (DisplayFields) -> T): CoreResult<T> = try {
        reply.useOnce { CoreResult.Success(body(DisplayFields(it))) }
    } catch (_: Exception) { CoreResult.Failure(CoreError.InvalidResponse()) }
    finally { reply.close() }
}
