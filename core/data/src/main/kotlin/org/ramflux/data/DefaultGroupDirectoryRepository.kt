// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.ramflux.sdk.*

/** [decoding] runs snapshot decoding and projection, never the caller's (main) thread. */
internal class DefaultGroupDirectoryRepository(
    transport: RepositoryTransport,
    private val decoding: CoroutineDispatcher = Dispatchers.Default,
) : GroupDirectoryRepository {
    private val source = DefaultGroupsRepository(transport)
    private val accounts = DefaultAccountsRepository(transport)
    private val people = DefaultContactDirectoryRepository(transport, decoding)
    private val directory = PeerDirectory(people)
    private data class Attempt(val id: String, val change: GroupChange, var result: ClientResult<GroupOutcome>?, val origin: GroupAuthority? = null)
    private val journal = mutableMapOf<String, Attempt>()
    private val groupPages = SnapshotPager<GroupSummary>(PAGE)
    private val memberPages = SnapshotPager<GroupMember>(PAGE)
    private val invitationPages = SnapshotPager<GroupInvitation>(PAGE)
    private val requestPages = SnapshotPager<GroupRequest>(PAGE)

    override suspend fun groupPage(account: String, query: String, cursor: String?): ClientResult<WindowedPage<GroupSummary>> {
        if (!valid(account) || query.length > MAX_QUERY) return invalid()
        return groupPages.page("$account\u0000$query", cursor) {
            filtered(list(account)) { query.isBlank() || it.name?.contains(query, true) == true || it.id.contains(query, true) }
        }
    }

    override suspend fun memberPage(account: String, group: String, query: String, cursor: String?): ClientResult<WindowedPage<GroupMember>> {
        if (!valid(account) || !valid(group) || query.length > MAX_QUERY) return invalid()
        return memberPages.page("$account\u0000$group\u0000$query", cursor) {
            val roster = decoded(source.members(RepositoryContext(account), buildJsonObject { put("group_id", group) }), { GroupMetadataReader.detail(it, decoding) }) {
                require(it.group.id == group)
                it.roster.map { member -> GroupMember(member.principal, member.root, member.device, member.role.appRole(), member.active) }
            }
            val known = if (roster is ClientResult.Ready) ClientResult.Ready(named(account, roster.value)) else roster
            filtered(known) {
                query.isBlank() || it.principal.contains(query, true) || it.device.contains(query, true) || it.name?.contains(query, true) == true
            }
        }
    }

    override suspend fun invitationPage(account: String, cursor: String?): ClientResult<WindowedPage<GroupInvitation>> {
        if (!valid(account)) return invalid()
        return invitationPages.page(account, cursor) { invitations(account) }
    }

    override suspend fun requestPage(account: String, group: String, cursor: String?): ClientResult<WindowedPage<GroupRequest>> {
        if (!valid(account) || !valid(group)) return invalid()
        return requestPages.page("$account\u0000$group", cursor) { requests(account, group) }
    }

    private suspend fun <T> filtered(rows: ClientResult<List<T>>, keep: (T) -> Boolean): ClientResult<List<T>> = when (rows) {
        is ClientResult.Ready -> ClientResult.Ready(withContext(decoding) { rows.value.filter(keep) })
        else -> rows
    }
    private fun valid(value: String) = value.isNotBlank() && value.length <= 4096
    override fun pending(account: String) = synchronized(journal) { journal[account]?.takeIf {
        it.result == null || (it.result as? ClientResult.Failed)?.problem?.outcome == ClientOutcome.UNKNOWN
    }?.let { PendingGroupOperation(it.id, it.change) } }
    override suspend fun list(account: String): ClientResult<List<GroupSummary>> {
        val result = decoded(source.list(RepositoryContext(account), buildJsonObject {}), { GroupMetadataReader.list(it, decoding) }) {
            it.map { row -> row.appGroup() }
        }
        val attempt = synchronized(journal) { journal[account] }
        if (attempt != null && pending(account) != null && attempt.change.operation == GroupOperation.CREATE &&
            result is ClientResult.Ready && result.value.any { it.id == attempt.change.group && it.name == attempt.change.name }) {
            val current = detail(account, attempt.change.group)
            if (current is ClientResult.Ready && current.value.own.authorized && current.value.ownRole == GroupRole.OWNER &&
                current.value.own.root == attempt.origin?.root && current.value.own.device == attempt.origin?.device) {
                synchronized(journal) { if (journal[account] === attempt) attempt.result = ClientResult.Ready(GroupOutcome(attempt.change.group, false, false, null)) }
            }
        }
        return result
    }
    override suspend fun detail(account: String, group: String): ClientResult<GroupDetail> {
        val authority = authority(account)
        if (authority !is ClientResult.Ready) return authority.failure()
        return decoded(source.members(RepositoryContext(account), buildJsonObject { put("group_id", group) }), { GroupMetadataReader.detail(it, decoding) }) {
            require(it.group.id == group)
            GroupDetail(it.group.appGroup(), it.roster.map { member -> GroupMember(member.principal, member.root, member.device, member.role.appRole(), member.active) }, authority.value)
        }.let { result -> if (result is ClientResult.Ready) ClientResult.Ready(result.value.copy(roster = named(account, result.value.roster))) else result }
    }

    /** Members named from the contact list, with the SDK-verified AI marker; one contact read per roster. */
    private suspend fun named(account: String, roster: List<GroupMember>): List<GroupMember> {
        val peers = directory.peers(account, roster.flatMapTo(HashSet()) { listOfNotNull(it.principal.takeIf(String::isNotBlank), it.root) })
        return roster.map { member ->
            val peer = peers[member.principal] ?: member.root?.let { peers[it] }
            if (peer == null) member else member.copy(name = peer.name, ai = peer.ai)
        }
    }
    private suspend fun authority(account: String): ClientResult<GroupAuthority> = decoded(accounts.accountStatus(RepositoryContext(account), buildJsonObject {}), GroupMetadataReader::authority) {
        require(it.account == account)
        GroupAuthority(it.principal, it.root, it.device, it.authorized)
    }
    override suspend fun invitations(account: String): ClientResult<List<GroupInvitation>> = decoded(source.invitationsList(RepositoryContext(account), buildJsonObject {}), { GroupMetadataReader.invitations(it, decoding) }) {
        it.map { row -> GroupInvitation(row.id, row.name, row.creator, row.inviter, row.expires) }
    }
    override suspend fun requests(account: String, group: String): ClientResult<List<GroupRequest>> {
        val result = decoded(source.requestsList(RepositoryContext(account), buildJsonObject { put("group_id", group) }), { GroupMetadataReader.requests(it, decoding) }) {
            require(it.all { row -> row.group == group }); it.map { row -> GroupRequest(row.id, row.group, row.status, row.waiting, row.expires) }
        }
        val attempt = synchronized(journal) { journal[account] }
        if (attempt != null && pending(account) != null && attempt.change.group == group && result is ClientResult.Ready) {
            result.value.find { it.id == attempt.id }?.let { request ->
                val resolved = when {
                    request.waiting -> ClientResult.Ready(GroupOutcome(group, true, false, request.id))
                    request.status in setOf("applied", "already_applied") -> ClientResult.Ready(GroupOutcome(group, false, false, request.id))
                    request.status == "capacity" -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, ClientFailureReason.GROUP_CAPACITY))
                    request.status == "invalid" -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.REFUSED, ClientFailureReason.GROUP_INVALID))
                    request.status == "expired" -> ClientResult.Ready(GroupOutcome(group, false, true, request.id))
                    request.status in setOf("permission_denied", "rejected", "refused") -> denied()
                    else -> null
                }
                if (resolved != null) synchronized(journal) { if (journal[account] === attempt) attempt.result = resolved }
            }
        }
        return result
    }
    /**
     * The group's conversation id: never derived from the group id. The `group.list` row names it; when the row does not
     * (yet), the group's metadata (`group.members`) names the conversation the SDK bound to it. `group.history` is the last
     * resort; it refuses the group id as conversation (invalid_argument) when the two differ.
     */
    override suspend fun conversation(account: String, group: String): ClientResult<String> {
        val listed = decoded(source.list(RepositoryContext(account), buildJsonObject {}), { GroupMetadataReader.listedConversation(it, group, decoding) }) { it }
        if (listed is ClientResult.Ready && listed.value != null) return ClientResult.Ready(requireNotNull(listed.value))
        val metadata = decoded(source.members(RepositoryContext(account), buildJsonObject { put("group_id", group) }), { GroupMetadataReader.metadataConversation(it, group) }) { it }
        clientDebugEvent("groups.conversation.metadata=" + (if (metadata is ClientResult.Ready) (if (metadata.value != null) "named" else "none") else "failed"))
        if (metadata is ClientResult.Ready && metadata.value != null) return ClientResult.Ready(requireNotNull(metadata.value))
        return decoded(source.history(RepositoryContext(account), buildJsonObject {
            put("group_id", group); put("conversation_id", group); put("limit", 0)
        }), { GroupMetadataReader.conversation(it, group) }) { it }
    }
    /**
     * Journal topics of the pinned SDK (`bus/journal/project.rs`): `group.changed` follows the group,
     * member and ban projections; `group.invitation` an invitation held for consent; `conversation.changed`
     * the group conversation rows.
     */
    override fun updates(account: String): Flow<ClientResult<Unit>> = source.updates(SubscriptionFilter(account, buildJsonObject {
        put("topics", buildJsonArray { GROUP_TOPICS.forEach { add(it) } })
    })).map { update ->
        when(update) {
            is CoreUpdate.Events -> { update.body.close(); ClientResult.Ready(Unit) }
            is CoreUpdate.Failure -> ClientResult.Failed(update.error.appProblem())
            // The shared event hub has already reset the native queue: reload the snapshot.
            is CoreUpdate.Terminal -> if (update.kind == "resync_required") ClientResult.Ready(Unit)
                else ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 27, ClientOutcome.NONE))
        }
    }
    override suspend fun mutate(account: String, change: GroupChange, operationId: String): ClientResult<GroupOutcome> {
        if (account.isBlank() || account.length > 4096 || change.group.isBlank() || change.group.length > 4096 ||
            !Regex("[a-z0-9-]{1,128}").matches(operationId)) return invalid()
        val previous = synchronized(journal) { journal[account] }
        if (previous != null && previous.id == operationId) {
            if (previous.change != change) return invalid()
            return previous.result ?: unknown()
        }
        if (pending(account) != null) return unknown()
        if (change.operation in setOf(GroupOperation.CREATE, GroupOperation.RENAME) &&
            (change.name.isBlank() || change.name.toByteArray().size > 512)) return invalid()
        val fields = linkedMapOf("group_id" to change.group)
        var origin: GroupAuthority? = null
        when(change.operation) {
            GroupOperation.CREATE -> {
                val own = authority(account)
                if (own !is ClientResult.Ready) return own.failure()
                if (!own.value.authorized) return denied()
                origin = own.value
                fields["creator_id"] = own.value.device
                fields["conversation_id"] = change.group
                fields["name"] = change.name
            }
            GroupOperation.ACCEPT, GroupOperation.DECLINE -> {
                val invitation = change.invitation ?: return invalid()
                val current = invitations(account)
                if (current !is ClientResult.Ready) return current.failure()
                if (invitation.id != change.group || invitation !in current.value || invitation.expiresAt <= System.currentTimeMillis() / 1000) return invalid()
            }
            GroupOperation.LEAVE -> {
                // Any member but the owner leaves through the signed group state (`group.leave`, CHAT-ACTIONS-01).
                val current = detail(account, change.group)
                if (current !is ClientResult.Ready) return current.failure()
                if (!current.value.canLeave) return denied()
            }
            else -> {
                val current = detail(account, change.group)
                if (current !is ClientResult.Ready) return current.failure()
                val detail = current.value
                if (!detail.canManage) return denied()
                if (change.epoch != detail.group.epoch) return invalid()
                when(change.operation) {
                    GroupOperation.RENAME -> fields["name"] = change.name
                    GroupOperation.ADD -> {
                        if (change.principal.isBlank() || change.principal.length > 2048 || change.root.isBlank() || change.root.length > 1024) return invalid()
                        val accepted = people.contacts(account)
                        if (accepted !is ClientResult.Ready) return accepted.failure()
                        if (accepted.value.none { it.principal == change.principal && it.root == change.root }) return denied()
                        // A contact is added by its principal id alone: the SDK selects the contact's active device from its
                        // verified identity record and checks the manifest root. With an explicit member_principal_commitment
                        // the SDK reads member_id as a literal device id, so a principal id there fails (code 16).
                        fields["member_id"] = change.principal; fields["role"] = "member"
                    }
                    GroupOperation.ROLE, GroupOperation.REMOVE -> {
                        val member = change.member ?: return invalid()
                        if (member !in detail.roster || !detail.canChange(member)) return denied()
                        fields["member_id"] = member.device; fields["member_principal_commitment"] = requireNotNull(member.root)
                        if (change.operation == GroupOperation.ROLE) {
                            if (change.role !in setOf(GroupRole.ADMIN, GroupRole.MEMBER)) return invalid()
                            fields["role"] = if (change.role == GroupRole.ADMIN) "admin" else "member"
                        }
                    }
                    else -> return invalid()
                }
            }
        }
        if (change.operation !in setOf(GroupOperation.CREATE, GroupOperation.ACCEPT, GroupOperation.DECLINE)) fields["request_id"] = operationId
        val attempt = Attempt(operationId, change, null, origin)
        val reserved = synchronized(journal) {
            if (pending(account) != null) false else if (journal.size >= 32 && account !in journal) false
            else { journal[account] = attempt; true }
        }
        if (!reserved) return unknown()
        try {
            val body = SecretBody.fields { fields.forEach { (key, value) -> publicText(key, value) } }
            val context = RepositoryContext(account, operationId)
            val reply = when(change.operation) {
                GroupOperation.CREATE -> source.create(context, body)
                GroupOperation.RENAME -> source.nameSet(context, body)
                GroupOperation.ADD -> source.memberAdd(context, body)
                GroupOperation.REMOVE -> source.memberRemove(context, body)
                GroupOperation.ROLE -> source.roleSet(context, body)
                GroupOperation.ACCEPT -> source.invitationAccept(context, body)
                GroupOperation.DECLINE -> source.invitationDecline(context, body)
                GroupOperation.LEAVE -> source.leave(context, body)
            }
            val accept = when(change.operation) { GroupOperation.ACCEPT -> true; GroupOperation.DECLINE -> false; else -> null }
            val result = decoded(reply, { GroupMetadataReader.outcome(it, change.group, accept) }) {
                GroupOutcome(it.group, it.waiting, it.expired, it.request, it.conversation)
            }
            synchronized(journal) { attempt.result = result }
            return result
        } catch (cancelled: CancellationException) {
            synchronized(journal) { attempt.result = unknown() }
            throw cancelled
        } catch (_: Exception) {
            val result = unknown(); synchronized(journal) { attempt.result = result }; return result
        }
    }
    private fun GroupDisplay.appGroup() = GroupSummary(id, name, epoch, members.toList())
    private fun String.appRole() = when(this) { "owner" -> GroupRole.OWNER; "admin" -> GroupRole.ADMIN; "member" -> GroupRole.MEMBER; "bot" -> GroupRole.BOT; else -> GroupRole.UNKNOWN }
    private fun invalid() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
    private fun denied() = ClientResult.Failed(ClientProblem(ClientFailureKind.AUTHORIZATION, 12, ClientOutcome.REFUSED))
    private fun unknown() = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 16, ClientOutcome.UNKNOWN))
    private fun <T> ClientResult<T>.failure(): ClientResult<Nothing> = when(this) {
        is ClientResult.Failed -> this; is ClientResult.Unavailable -> this; is ClientResult.Ready -> invalid()
    }
    private suspend fun <T,R> decoded(reply: CoreResult<SecretReply>, reader: suspend (SecretReply) -> CoreResult<T>, map: (T) -> R): ClientResult<R> = when(reply) {
        is CoreResult.Failure -> ClientResult.Failed(reply.error.appProblem())
        is CoreResult.Success -> when(val result = reader(reply.value)) {
            is CoreResult.Failure -> ClientResult.Failed(result.error.appProblem())
            // Projection of a several-thousand-row snapshot stays off the caller's (main) thread.
            is CoreResult.Success -> try { ClientResult.Ready(withContext(decoding) { map(result.value) }) } catch (_: Exception) { invalid() }
        }
    }
}

private const val PAGE = 100
private const val MAX_QUERY = 256

/** `group.invitation` (CHAT-ACTIONS-01) announces an invitation held for consent, so the list shows it at once. */
internal val GROUP_TOPICS = listOf("group.changed", "group.invitation", "conversation.changed")
