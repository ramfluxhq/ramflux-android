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
}
class FakeGroupAccounts : AccountAccessRepository {
    var value: ClientResult<AccountCatalog> = ClientResult.Ready(AccountCatalog(listOf(AccountSummary("a", "Work", true, "unlocked")), "a"))
    override suspend fun list() = value
    override suspend fun select(id: String): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
    override suspend fun setup(action: AccountSetupAction): ClientResult<Unit> = ClientResult.Unavailable(ClientFeature.ONBOARDING)
}
class FakeGroupContacts : ContactDirectoryRepository {
    override suspend fun contacts(account: String): ClientResult<List<ContactSummary>> = ClientResult.Ready(emptyList())
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
