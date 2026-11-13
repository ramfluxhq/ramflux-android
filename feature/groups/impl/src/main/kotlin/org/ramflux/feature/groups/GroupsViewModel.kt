// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.groups.impl.R

@HiltViewModel
class GroupsViewModel @Inject constructor(
    private val groups: GroupDirectoryRepository,
    private val contacts: ContactDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(GroupsState())
    private val channel = Channel<GroupsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) {
            value.copy(
                groups = value.groups.filter {
                    value.query.isBlank() || it.title.contains(value.query, true) || it.id.contains(value.query, true)
                }.toPersistentList()
            )
        } else {
            GroupsState(problem = uiText(R.string.groups_closed))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), GroupsState())
    private var active = false
    private var request: Job? = null
    private var watch: Job? = null
    private var watchedAccount: String? = null
    private var hold: Job? = null
    private var ticket = 0L
    private var revision = 0L
    private var needsRefresh = false
    private var owner: String? = null
    private var detail: GroupDetail? = null
    private var inviteCache: List<GroupInvitation> = emptyList()
    private var contactCache: List<ContactSummary> = emptyList()
    private var prepared: Pair<String, GroupChange>? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            stop()
            local.value = GroupsState()
            if (active && it.first == ClientPhase.OPEN) read()
        }
    }
    fun onAction(action: GroupsAction) {
        if (local.value.confirmation != null && action !is GroupsAction.HoldEnd && action !is GroupsAction.Visible &&
            action !in setOf(GroupsAction.HoldStart, GroupsAction.Deny)
        ) {
            return
        }
        when (action) {
            is GroupsAction.Visible -> {
                active = action.value
                if (active) {
                    read()
                } else {
                    stop()
                    local.value = GroupsState()
                }
            }
            GroupsAction.Refresh -> if (request?.isActive != true) read()
            GroupsAction.Back -> if (local.value.page == GroupsPage.LIST) event(GroupsEvent.Back) else page(GroupsPage.LIST, null)
            GroupsAction.SetupAccount -> event(GroupsEvent.SetupAccount)
            is GroupsAction.Page -> page(action.page, action.id)
            is GroupsAction.Query -> local.update { it.copy(query = action.value.take(256)) }
            is GroupsAction.Name -> if (!local.value.busy && action.value.toByteArray().size <= 512) {
                local.update {
                    it.copy(
                        name = action.value
                    )
                }
            }
            GroupsAction.Create -> if (!local.value.loading && local.value.name.isNotBlank()) {
                prepare(
                    GroupChange(GroupOperation.CREATE, UUID.randomUUID().toString(), name = local.value.name)
                )
            }
            GroupsAction.Rename -> detail?.takeIf { local.value.authorityFresh && it.canManage }?.let {
                prepare(GroupChange(GroupOperation.RENAME, it.group.id, it.group.epoch, name = local.value.name))
            }
            is GroupsAction.Add -> detail?.takeIf { local.value.authorityFresh && it.canManage }?.let { current ->
                contactCache.find { it.id == action.id && it.root != null }?.let {
                    prepare(
                        GroupChange(
                            GroupOperation.ADD,
                            current.group.id,
                            current.group.epoch,
                            principal = it.principal,
                            root = requireNotNull(it.root)
                        )
                    )
                }
            }
            is GroupsAction.Role -> member(action.id)?.let { (current, selected) ->
                prepare(
                    GroupChange(
                        GroupOperation.ROLE,
                        current.group.id,
                        current.group.epoch,
                        member = selected,
                        role = if (action.admin) GroupRole.ADMIN else GroupRole.MEMBER
                    )
                )
            }
            is GroupsAction.Remove -> member(action.id)?.let { (current, selected) ->
                prepare(GroupChange(GroupOperation.REMOVE, current.group.id, current.group.epoch, member = selected))
            }
            is GroupsAction.Invitation -> inviteCache.find {
                it.id == local.value.selected && it.expiresAt > System.currentTimeMillis() / 1000
            }?.let {
                prepare(GroupChange(if (action.accept) GroupOperation.ACCEPT else GroupOperation.DECLINE, it.id, invitation = it))
            }
            GroupsAction.OpenChat -> navigateChat()
            GroupsAction.Unavailable -> local.update { it.copy(problem = uiText(R.string.groups_unavailable)) }
            GroupsAction.Deny -> {
                hold?.cancel()
                prepared = null
                local.update { it.copy(confirmation = null) }
            }
            GroupsAction.HoldStart -> if (hold?.isActive != true && local.value.confirmation?.held == false) {
                hold = viewModelScope.launch {
                    delay(1_200)
                    local.update { it.copy(confirmation = it.confirmation?.copy(held = true)) }
                }
            }
            is GroupsAction.HoldEnd -> {
                hold?.cancel()
                if (action.released && local.value.confirmation?.held == true) {
                    mutate()
                } else {
                    local.update { it.copy(confirmation = it.confirmation?.copy(held = false)) }
                }
            }
        }
    }
    private fun member(id: String): Pair<GroupDetail, GroupMember>? {
        val current = detail ?: return null
        if (!local.value.authorityFresh) return null
        return current.roster.find { it.id == id && current.canChange(it) }?.let { current to it }
    }
    private fun page(page: GroupsPage, id: String?) {
        if (local.value.busy) return
        if (page in setOf(GroupsPage.DETAIL, GroupsPage.MEMBERS, GroupsPage.REQUESTS) &&
            !local.value.groups.any { it.id == id }
        ) {
            return
        }
        if (page == GroupsPage.INVITATION && inviteCache.none { it.id == id }) return
        request?.cancel()
        ticket++
        detail = null
        prepared = null
        hold?.cancel()
        local.update {
            it.copy(
                page = page, selected = id, title = null, confirmation = null, name = "", problem = null, notice = null,
                members = persistentListOf(), requests = persistentListOf(), canManage = false, authorityFresh = false
            )
        }
        read()
    }
    private fun stop() {
        request?.cancel()
        watch?.cancel()
        hold?.cancel()
        watchedAccount = null
        owner = null
        prepared = null
        detail = null
        contactCache = emptyList()
        inviteCache = emptyList()
        ticket++
    }
    private fun event(event: GroupsEvent) {
        stop()
        local.value = GroupsState()
        viewModelScope.launch { channel.send(event) }
    }
    private suspend fun account(expected: Long, generation: Long, required: String? = null): AccountSummary? {
        val result = accounts.list()
        current(expected, generation)
        when (result) {
            is ClientResult.Failed -> {
                conceal(problem = result.problem.message())
                return null
            }
            is ClientResult.Unavailable -> {
                conceal(problem = uiText(R.string.groups_unavailable))
                return null
            }
            is ClientResult.Ready -> {
                val selected = result.value.accounts.find { it.id == result.value.activeId && it.unlocked }
                if (selected == null) {
                    conceal(required = true)
                    return null
                }
                if (required != null && selected.id != required) {
                    conceal(problem = uiText(R.string.groups_account_changed))
                    return null
                }
                return selected
            }
        }
    }
    private fun conceal(problem: UiText? = null, required: Boolean = false) {
        owner = null
        detail = null
        prepared = null
        hold?.cancel()
        watch?.cancel()
        watchedAccount = null
        local.value = GroupsState(problem = problem, accountRequired = required)
    }
    private fun observe(account: String) {
        if (watchedAccount == account && watch?.isActive == true) return
        watch?.cancel()
        watchedAccount = account
        watch = viewModelScope.launch {
            groups.updates(account).collect { update ->
                if (!active || owner != account) return@collect
                revision++
                prepared = null
                hold?.cancel()
                local.update {
                    it.copy(
                        authorityFresh = false, canManage = false, confirmation = null,
                        members = it.members.map { member -> member.copy(manageable = false) }.toPersistentList()
                    )
                }
                when (update) {
                    is ClientResult.Ready -> if (request?.isActive == true) needsRefresh = true else read()
                    is ClientResult.Failed -> local.update { it.copy(problem = update.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.groups_unavailable)) }
                }
            }
        }
    }
    private fun read() {
        if (!active || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val observed = revision
        local.update { it.copy(loading = true, loaded = false, problem = null, authorityFresh = false, canManage = false) }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation, owner) ?: return@launch
                owner = selected.id
                observe(selected.id)
                val list = groups.list(selected.id)
                current(expected, generation)
                val invites = groups.invitations(selected.id)
                current(expected, generation)
                val group = local.value.selected
                val detailResult = if (group != null && local.value.page != GroupsPage.INVITATION) {
                    groups.detail(selected.id, group)
                } else {
                    null
                }
                current(expected, generation)
                val requests = if (group != null && local.value.page == GroupsPage.REQUESTS) groups.requests(selected.id, group) else null
                current(expected, generation)
                val people = if (local.value.page == GroupsPage.MEMBERS) contacts.contacts(selected.id) else null
                if (account(expected, generation, selected.id) == null) return@launch
                if (observed != revision) {
                    needsRefresh = true
                    return@launch
                }
                val rows = when (list) {
                    is ClientResult.Ready -> list.value
                    else -> {
                        failure(list)
                        return@launch
                    }
                }
                if (rows.size > 512 || rows.map { it.id }.distinct().size != rows.size) {
                    local.update { it.copy(problem = uiText(R.string.groups_invalid)) }
                    return@launch
                }
                inviteCache = when (invites) {
                    is ClientResult.Ready -> invites.value
                    else -> {
                        failure(invites)
                        emptyList()
                    }
                }
                detail = (detailResult as? ClientResult.Ready)?.value
                detailResult?.takeUnless { it is ClientResult.Ready }?.let { failure(it) }
                contactCache = (people as? ClientResult.Ready)?.value ?: emptyList()
                people?.takeUnless { it is ClientResult.Ready }?.let { failure(it) }
                local.update {
                    it.copy(
                        account = selected.label ?: selected.id, loaded = true,
                        groups = rows.map { row ->
                            GroupRow(row.id, row.name ?: row.id, UiText.Quantity(R.plurals.groups_member_count, row.members.size.toULong()))
                        }.toPersistentList(),
                        invitations = inviteCache.map { row ->
                            InviteRow(row.id, row.name ?: row.id, row.creator, row.inviter, UiText.DateTime(row.expiresAt))
                        }.toPersistentList(),
                        contacts = contactCache.filter { row -> row.root != null }.map { row ->
                            GroupRow(row.id, row.name ?: row.principal, UiText.Data(row.principal))
                        }.toPersistentList(),
                        requests = (requests as? ClientResult.Ready)?.value.orEmpty().map { row ->
                            RequestRow(row.id, row.status.requestLabel(row.waiting), UiText.DateTime(row.expiresAt))
                        }.toPersistentList(),
                        pending = groups.pending(selected.id)?.operationId
                    )
                }
                requests?.takeUnless { it is ClientResult.Ready }?.let { failure(it) }
                if (local.value.page == GroupsPage.INVITATION && inviteCache.none { it.id == local.value.selected }) {
                    local.update { it.copy(problem = uiText(R.string.groups_expired)) }
                }
                projectDetail()
                local.update { screen -> screen.copy(selectedInvitation = screen.invitations.find { it.id == screen.selected }) }
            } finally {
                if (expected == ticket) {
                    local.update { it.copy(loading = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read()
                    }
                }
            }
        }
    }
    private fun projectDetail() {
        val current = detail
        local.update {
            it.copy(
                title = current?.group?.name ?: current?.group?.id,
                epoch = current?.group?.epoch?.let { value -> UiText.Unsigned(value) } ?: UiText.Empty,
                canManage = current?.canManage == true,
                authorityFresh = current != null,
                members = current?.roster.orEmpty().map { row ->
                    MemberRow(
                        row.id,
                        row.principal,
                        row.root,
                        row.device,
                        row.role.label(),
                        row.active,
                        current?.canChange(row) == true
                    )
                }.toPersistentList()
            )
        }
    }
    private fun prepare(change: GroupChange) {
        if (local.value.loading || local.value.busy || local.value.pending != null || owner == null) return
        val id = UUID.randomUUID().toString()
        prepared = id to change
        val values = buildList {
            add(uiText(R.string.groups_fact_account, UiText.Data(requireNotNull(owner))))
            add(uiText(R.string.groups_fact_group, UiText.Data(change.group)))
            change.epoch?.let { add(uiText(R.string.groups_fact_epoch, UiText.Unsigned(it))) }
            if (change.name.isNotEmpty()) add(uiText(R.string.groups_fact_name, UiText.Data(change.name)))
            change.member?.let {
                add(UiText.Data(it.principal))
                add(UiText.Data(it.root.orEmpty()))
                add(UiText.Data(it.device))
            }
            if (change.principal.isNotEmpty()) {
                add(UiText.Data(change.principal))
                add(UiText.Data(change.root))
            }
            if (change.operation == GroupOperation.ROLE) add(change.role.label())
            change.invitation?.let {
                add(UiText.Data(it.creator))
                add(UiText.Data(it.inviter))
                add(UiText.DateTime(it.expiresAt))
            }
        }.toPersistentList()
        local.update { it.copy(confirmation = GroupFacts(change.operation.label(), values)) }
    }
    private fun mutate() {
        val attempt = prepared ?: return
        val accountId = owner ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        local.update { it.copy(busy = true, confirmation = null, problem = null) }
        request = viewModelScope.launch {
            try {
                if (account(expected, generation, accountId) == null) return@launch
                val result = groups.mutate(accountId, attempt.second, attempt.first)
                current(expected, generation)
                if (account(expected, generation, accountId) == null) return@launch
                local.update { it.copy(pending = groups.pending(accountId)?.operationId) }
                when (result) {
                    is ClientResult.Ready -> {
                        local.update {
                            it.copy(
                                notice = uiText(
                                    when {
                                        result.value.expired -> R.string.groups_expired
                                        result.value.waiting -> R.string.groups_waiting
                                        else -> R.string.groups_acknowledged
                                    }
                                )
                            )
                        }
                        if (attempt.second.operation == GroupOperation.CREATE && !result.value.expired && !result.value.waiting) {
                            val readback = groups.detail(accountId, result.value.group)
                            current(expected, generation)
                            if (readback !is ClientResult.Ready) {
                                failure(readback)
                                return@launch
                            }
                            if (readback.value.group.id != result.value.group || !readback.value.own.authorized ||
                                readback.value.ownRole != GroupRole.OWNER
                            ) {
                                local.update { it.copy(problem = uiText(R.string.groups_access_changed)) }
                                return@launch
                            }
                            val destination = groups.conversation(accountId, result.value.group)
                            current(expected, generation)
                            if (account(expected, generation, accountId) == null) return@launch
                            when (destination) {
                                is ClientResult.Ready -> {
                                    channel.send(GroupsEvent.OpenChat(accountId, destination.value))
                                    return@launch
                                }
                                else -> failure(destination)
                            }
                        } else {
                            needsRefresh = true
                        }
                    }
                    else -> failure(result)
                }
            } finally {
                prepared = null
                if (expected == ticket) {
                    local.update { it.copy(busy = false) }
                    if (needsRefresh) {
                        needsRefresh = false
                        read()
                    }
                }
            }
        }
    }
    private fun navigateChat() {
        val group = local.value.selected ?: return
        val owner = owner ?: return
        if (request?.isActive == true) return
        val expected = ++ticket
        val generation = session.status.value.generation
        request = viewModelScope.launch {
            if (account(expected, generation, owner) == null) return@launch
            val result = groups.conversation(owner, group)
            current(expected, generation)
            if (account(expected, generation, owner) == null) return@launch
            when (result) {
                is ClientResult.Ready -> channel.send(GroupsEvent.OpenChat(owner, result.value))
                else -> failure(result)
            }
        }
    }
    private fun failure(result: ClientResult<*>) {
        local.update {
            it.copy(
                problem = when (result) {
                    is ClientResult.Failed -> result.problem.message()
                    is ClientResult.Unavailable -> uiText(R.string.groups_unavailable)
                    is ClientResult.Ready -> null
                }
            )
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!active || expected != ticket || generation != session.status.value.generation ||
            session.status.value.phase != ClientPhase.OPEN
        ) {
            throw CancellationException(
                "retired groups"
            )
        }
    }
}
private fun ClientProblem.message() = uiText(
    if (outcome == ClientOutcome.UNKNOWN) R.string.groups_unknown else R.string.groups_error_code,
    UiText.Number(code.toLong())
)
private fun GroupOperation.label() = uiText(
    when (this) {
        GroupOperation.CREATE -> R.string.groups_create
        GroupOperation.RENAME -> R.string.groups_rename
        GroupOperation.ADD -> R.string.groups_add
        GroupOperation.REMOVE -> R.string.groups_remove
        GroupOperation.ROLE -> R.string.groups_role
        GroupOperation.ACCEPT -> R.string.groups_accept
        GroupOperation.DECLINE -> R.string.groups_decline
    }
)
private fun GroupRole.label() = uiText(
    when (this) {
        GroupRole.OWNER -> R.string.groups_owner
        GroupRole.ADMIN -> R.string.groups_admin
        GroupRole.MEMBER -> R.string.groups_member
        GroupRole.BOT -> R.string.groups_bot
        GroupRole.UNKNOWN -> R.string.groups_unknown_role
    }
)
private fun String.requestLabel(waiting: Boolean) = uiText(
    when {
        waiting -> R.string.groups_waiting
        this == "applied" -> R.string.groups_applied
        this == "expired" -> R.string.groups_expired
        this in setOf("permission_denied", "rejected", "refused") -> R.string.groups_refused
        else -> R.string.groups_unknown_request
    }
)
