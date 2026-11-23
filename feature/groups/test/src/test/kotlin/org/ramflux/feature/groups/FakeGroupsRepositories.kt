// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
fun groupDetail(role: GroupRole = GroupRole.OWNER) = GroupDetail(
    GroupSummary("g", "Actual group", 7u, listOf("own", "peer")),
    listOf(GroupMember("self", "root", "own", role, true), GroupMember("peer", "peer-root", "peer", GroupRole.MEMBER, true)),
    GroupAuthority("self", "root", "own", true)
)
class FakeGroups : GroupDirectoryRepository {
    var reads = 0
    var listing: suspend () -> ClientResult<List<GroupSummary>> = { ClientResult.Ready(listOf(groupDetail().group)) }
    var detailing: suspend (
        String
    ) -> ClientResult<GroupDetail> = { ClientResult.Ready(groupDetail().copy(group = groupDetail().group.copy(id = it))) }
    val changes = mutableListOf<Pair<String, GroupChange>>()
    var outcome: ClientResult<GroupOutcome> = ClientResult.Ready(GroupOutcome("g", false, false, null))
    var resolving: ClientResult<String> = ClientResult.Ready("canonical")
    val invalidations = MutableSharedFlow<ClientResult<Unit>>(extraBufferCapacity = 8)
    override suspend fun list(account: String): ClientResult<List<GroupSummary>> {
        reads++
        return listing()
    }
    override suspend fun detail(account: String, group: String) = detailing(group)
    override suspend fun invitations(account: String): ClientResult<List<GroupInvitation>> = ClientResult.Ready(emptyList())
    override suspend fun requests(account: String, group: String): ClientResult<List<GroupRequest>> = ClientResult.Ready(emptyList())
    override suspend fun conversation(account: String, group: String) = resolving
    override suspend fun mutate(account: String, change: GroupChange, operationId: String): ClientResult<GroupOutcome> {
        changes.add(operationId to change)
        return (outcome as? ClientResult.Ready)?.let { ClientResult.Ready(it.value.copy(group = change.group)) } ?: outcome
    }
    override fun pending(account: String): PendingGroupOperation? = null
    override fun updates(account: String) = invalidations
    val cursors = mutableListOf<Pair<String, String?>>()
    override suspend fun groupPage(account: String, query: String, cursor: String?): ClientResult<WindowedPage<GroupSummary>> {
        cursors.add("groups" to cursor)
        return fakePage(list(account), cursor) { query.isBlank() || it.name?.contains(query, true) == true }
    }
    override suspend fun memberPage(
        account: String,
        group: String,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<GroupMember>> {
        cursors.add("members" to cursor)
        val roster = when (val value = rostering(group)) {
            is ClientResult.Ready -> ClientResult.Ready(value.value)
            is ClientResult.Failed -> value
            is ClientResult.Unavailable -> value
        }
        return fakePage(roster, cursor) { query.isBlank() || it.principal.contains(query, true) }
    }
    override suspend fun invitationPage(account: String, cursor: String?) = fakePage(invitations(account), cursor) { true }
    override suspend fun requestPage(account: String, group: String, cursor: String?) = fakePage(requests(account, group), cursor) { true }

    /** The roster `memberPage` serves; by default the roster of [detailing]. */
    var rostering: suspend (String) -> ClientResult<List<GroupMember>> = { group ->
        when (val value = detailing(group)) {
            is ClientResult.Ready -> ClientResult.Ready(value.value.roster)
            is ClientResult.Failed -> value
            is ClientResult.Unavailable -> value
        }
    }
}

/** Offset pages of 100 rows over a fake snapshot, as the data layer's snapshot pager serves them. */
fun <T> fakePage(rows: ClientResult<List<T>>, cursor: String?, keep: (T) -> Boolean): ClientResult<WindowedPage<T>> = when (rows) {
    is ClientResult.Ready -> {
        val all = rows.value.filter(keep)
        val offset = cursor?.toInt() ?: 0
        val end = minOf(offset + 100, all.size)
        ClientResult.Ready(
            WindowedPage(
                all.subList(offset, end).toList(),
                if (end < all.size) "$end" else null,
                if (offset > 0) "${maxOf(offset - 100, 0)}" else null
            )
        )
    }
    is ClientResult.Failed -> rows
    is ClientResult.Unavailable -> rows
}
class FakeGroupAccounts : AccountAccessRepository {
    var value: ClientResult<AccountCatalog> = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a"))
    override suspend fun list() = value
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeGroupContacts : ContactDirectoryRepository {
    var people: List<ContactSummary> = emptyList()
    val scopes = mutableListOf<ContactListScope>()
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> = ClientResult.Ready(people)
    override suspend fun contactPage(
        account: String,
        scope: ContactListScope,
        query: String,
        cursor: String?
    ): ClientResult<WindowedPage<ContactSummary>> {
        scopes.add(scope)
        return fakePage(ClientResult.Ready(people), cursor) { query.isBlank() || it.name?.contains(query, true) == true }
    }
    override suspend fun requests(account: String): ClientResult<List<ContactRequestSummary>> = ClientResult.Ready(emptyList())
    override suspend fun safety(account: String, root: String): ClientResult<ContactSafety> =
        ClientResult.Unavailable(ClientFeature.CONTACTS)
    override suspend fun submit(command: ContactCommand, confirmed: Boolean): ClientResult<Unit> =
        ClientResult.Unavailable(ClientFeature.CONTACTS)
    override fun attempts(account: String): List<ContactAttempt> = emptyList()
    override suspend fun availability(operation: ContactOperation): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.CONTACTS)
}
class FakeGroupSession : ClientSessionRepository {
    override val status = MutableStateFlow(ClientSnapshot(ClientPhase.OPEN, 1))
    override suspend fun foreground() {}
    override suspend fun background() {
        status.value = status.value.copy(phase = ClientPhase.CLOSED)
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> = ClientResult.Ready(Unit)
}
