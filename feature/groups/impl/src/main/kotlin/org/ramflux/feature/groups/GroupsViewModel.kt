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

/**
 * Groups, invitations, the roster, the invite picker and group requests, each read through the repository's
 * window pages into its own bounded [PageWindow]; filtering runs in the data layer off the main thread. The
 * group header (name, epoch, own role) is read separately and keeps only the own roster row. While visible
 * the screen follows the group journal topics through the shared event hub and reloads the visible windows.
 */
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
        if (status.phase == ClientPhase.OPEN) value else GroupsState(problem = uiText(R.string.groups_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), GroupsState())

    /** One windowed list: its bounded window, its page source and the job reading its next or previous page. */
    private class Feed<T>(
        id: (T) -> String,
        private val source: suspend (String, String?, String, String?) -> ClientResult<WindowedPage<T>>
    ) {
        val window = PageWindow(WINDOW_PAGES, id)
        var job: Job? = null

        /**
         * Reads one page for (account, group, query, cursor). The returned action places it once the read is known
         * to be current: a new window ([forward] null), after the window or before it. A failure is returned too.
         */
        suspend fun stage(
            account: String,
            group: String?,
            query: String,
            cursor: String?,
            forward: Boolean?
        ): Pair<ClientResult<*>?, () -> Unit> = when (val result = source(account, group, query, cursor)) {
            is ClientResult.Ready -> null to {
                when (forward) {
                    null -> window.reset(result.value)
                    true -> window.append(result.value)
                    false -> window.prepend(result.value)
                }
            }
            else -> result to { if (forward == null) clear() }
        }

        fun clear() = window.reset(WindowedPage(emptyList(), null, null))
    }
    private val groupFeed = Feed<GroupSummary>({ it.id }) { account, _, query, cursor -> groups.groupPage(account, query, cursor) }
    private val inviteFeed = Feed<GroupInvitation>({ it.id }) { account, _, _, cursor -> groups.invitationPage(account, cursor) }
    private val memberFeed = Feed<GroupMember>({ it.id }) { account, group, query, cursor ->
        groups.memberPage(account, requireNotNull(group), query, cursor)
    }
    private val contactFeed = Feed<ContactSummary>({ it.id }) { account, _, query, cursor ->
        contacts.contactPage(account, ContactListScope.ACCEPTED, query, cursor)
    }
    private val requestFeed =
        Feed<GroupRequest>({ it.id }) { account, group, _, cursor -> groups.requestPage(account, requireNotNull(group), cursor) }
    private val feeds = mapOf(
        GroupList.GROUPS to groupFeed,
        GroupList.INVITATIONS to inviteFeed,
        GroupList.MEMBERS to memberFeed,
        GroupList.CONTACTS to contactFeed,
        GroupList.REQUESTS to requestFeed
    )

    private var active = false
    private var request: Job? = null
    private var queryJob: Job? = null
    private var watch: Job? = null
    private var watchedAccount: String? = null
    private var ticket = 0L
    private var needsRefresh = false

    /** The ticket of the mutation that set `busy`; a refresh that retires the ticket meanwhile must not leave it set. */
    private var busyOwner = 0L
    private var owner: String? = null

    /** The selected group with only the own roster row kept, plus the roster size at that read. */
    private var header: Pair<GroupDetail, Int>? = null
    private var prepared: Pair<String, GroupChange>? = null

    /** The page the screen was entered at ([GroupsAction.Enter]); back from it leaves the screen. */
    private var entry: GroupsPage? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            stop()
            local.value = GroupsState()
            if (active && it.first == ClientPhase.OPEN) read()
        }
    }
    fun onAction(action: GroupsAction) {
        if (local.value.confirmation != null && action != GroupsAction.Confirm && action !is GroupsAction.Visible &&
            action != GroupsAction.Deny
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
            GroupsAction.Back -> {
                val parent = local.value.page.parent()
                clientDebugEvent("groups.back:" + local.value.page + ":" + parent + ":entry:" + entry + ":busy:" + local.value.busy)
                if (parent == null || local.value.page == entry) {
                    event(GroupsEvent.Back)
                } else {
                    page(
                        parent,
                        local.value.selected.takeIf {
                            parent != GroupsPage.LIST
                        }
                    )
                }
            }
            GroupsAction.SetupAccount -> event(GroupsEvent.SetupAccount)
            is GroupsAction.Page -> page(action.page, action.id)
            is GroupsAction.Enter -> if (!local.value.busy) {
                entry = GroupsPage.DETAIL
                local.update { it.copy(selected = action.id) }
                page(GroupsPage.DETAIL, action.id)
            }
            is GroupsAction.More -> more(action.list, action.forward)
            is GroupsAction.Query -> {
                val text = action.value.take(MAX_QUERY)
                if (text == local.value.query) return
                local.update { it.copy(query = text) }
                queryJob?.cancel()
                queryJob = viewModelScope.launch {
                    delay(QUERY_DEBOUNCE_MS)
                    read(listsOnly = true)
                }
            }
            is GroupsAction.Name -> if (!local.value.busy && action.value.toByteArray().size <= 512) {
                local.update { it.copy(name = action.value) }
            }
            GroupsAction.Create -> if (!local.value.loading && local.value.name.isNotBlank()) {
                prepare(GroupChange(GroupOperation.CREATE, UUID.randomUUID().toString(), name = local.value.name))
            }
            GroupsAction.Rename -> manageable()?.let {
                prepare(GroupChange(GroupOperation.RENAME, it.group.id, it.group.epoch, name = local.value.name))
            }
            is GroupsAction.Add -> manageable()?.let { current ->
                contactFeed.window.rows().find { it.id == action.id && it.root != null }?.let {
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
            GroupsAction.Leave -> header?.first?.takeIf { local.value.authorityFresh && it.canLeave }?.let { current ->
                prepare(GroupChange(GroupOperation.LEAVE, current.group.id, current.group.epoch))
            }
            is GroupsAction.Remove -> member(action.id)?.let { (current, selected) ->
                prepare(GroupChange(GroupOperation.REMOVE, current.group.id, current.group.epoch, member = selected))
            }
            is GroupsAction.Invitation -> inviteFeed.window.rows().find {
                it.id == local.value.selected && it.expiresAt > System.currentTimeMillis() / 1000
            }?.let {
                prepare(GroupChange(if (action.accept) GroupOperation.ACCEPT else GroupOperation.DECLINE, it.id, invitation = it))
            }
            GroupsAction.OpenChat -> navigateChat()
            is GroupsAction.Unavailable -> local.update {
                it.copy(
                    notice = uiText(
                        when (action.control) {
                            GroupControl.TRANSFER -> R.string.groups_transfer_unavailable
                        }
                    )
                )
            }
            GroupsAction.Deny -> {
                prepared = null
                local.update { it.copy(confirmation = null) }
            }
            // A list refresh still running is retired by the mutation; only another mutation in flight blocks it.
            GroupsAction.Confirm -> if (local.value.confirmation != null && !local.value.busy) mutate()
        }
    }
    private fun manageable(): GroupDetail? = header?.first?.takeIf { local.value.authorityFresh && it.canManage }
    private fun member(id: String): Pair<GroupDetail, GroupMember>? {
        val current = manageable() ?: return null
        return memberFeed.window.rows().find { it.id == id && current.canChange(it) }?.let { current to it }
    }
    private fun page(page: GroupsPage, id: String?) {
        if (local.value.busy) return
        val screen = local.value
        val allowed = when (page) {
            GroupsPage.LIST, GroupsPage.CREATE -> true
            GroupsPage.DETAIL, GroupsPage.MEMBERS, GroupsPage.REQUESTS, GroupsPage.INVITE ->
                id != null && (id == screen.selected || screen.groups.any { it.id == id })
            GroupsPage.MEMBER -> id != null && screen.members.any { it.id == id }
            GroupsPage.INVITATION -> id != null && screen.invitations.any { it.id == id }
        }
        if (!allowed) return
        val group = when (page) {
            GroupsPage.LIST, GroupsPage.CREATE -> null
            GroupsPage.MEMBER -> screen.selected
            else -> id
        }
        retire()
        prepared = null
        val sameGroup = group == screen.selected
        if (!sameGroup) header = null
        val keepRows = page == GroupsPage.MEMBER || (page == GroupsPage.MEMBERS && screen.page == GroupsPage.MEMBER)
        if (!keepRows) memberFeed.clear()
        local.update {
            it.copy(
                page = page, selected = group, confirmation = null, name = "", query = "", problem = null, notice = null,
                selectedMember = if (page == GroupsPage.MEMBER) it.members.find { row -> row.id == id } else null,
                selectedInvitation = if (page == GroupsPage.INVITATION) it.invitations.find { row -> row.id == id } else null,
                members = if (keepRows) it.members else persistentListOf(),
                contacts = persistentListOf(), requests = persistentListOf(),
                canManage = false, authorityFresh = false, loaded = false,
                title = if (sameGroup) it.title else it.groups.find { row -> row.id == group }?.title,
                epoch = if (sameGroup) it.epoch else UiText.Empty,
                memberCount = if (sameGroup) it.memberCount else UiText.Empty,
                ownRole = if (sameGroup) it.ownRole else null
            )
        }
        if (page != GroupsPage.CREATE) read() else local.update { it.copy(loaded = true) }
    }

    /**
     * Cancels every read and mutation in flight; results that arrive afterwards are discarded. A retired read never
     * clears its own loading flag (its ticket is stale), so it is cleared here; a new read sets it again.
     */
    private fun retire() {
        request?.cancel()
        queryJob?.cancel()
        feeds.values.forEach { it.job?.cancel() }
        ticket++
        local.update { it.copy(loading = false) }
    }
    private fun stop() {
        retire()
        watch?.cancel()
        watchedAccount = null
        owner = null
        prepared = null
        header = null
        feeds.values.forEach { it.clear() }
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
        header = null
        prepared = null
        feeds.values.forEach { it.clear() }
        watch?.cancel()
        watchedAccount = null
        local.value = GroupsState(problem = problem, accountRequired = required)
    }
    private fun observe(account: String) {
        if (watchedAccount == account && watch?.isActive == true) return
        watch?.cancel()
        watchedAccount = account
        watch = viewModelScope.launch {
            groups.updates(account).conflate().collect { update ->
                if (!active || owner != account) return@collect
                // Authority is stale until the header is read again: revoke the controls and any open review.
                prepared = null
                local.update {
                    it.copy(
                        authorityFresh = false, canManage = false, confirmation = null,
                        members = it.members.map { member -> member.copy(manageable = false) }.toPersistentList(),
                        selectedMember = it.selectedMember?.copy(manageable = false)
                    )
                }
                when (update) {
                    is ClientResult.Ready -> if (local.value.busy) needsRefresh = true else read()
                    is ClientResult.Failed -> local.update { it.copy(problem = update.problem.message()) }
                    is ClientResult.Unavailable -> local.update { it.copy(problem = uiText(R.string.groups_unavailable)) }
                }
            }
        }
    }

    /** The windowed lists the current page shows. */
    private fun GroupsPage.lists(): List<GroupList> = when (this) {
        GroupsPage.LIST -> listOf(GroupList.GROUPS, GroupList.INVITATIONS)
        GroupsPage.MEMBERS, GroupsPage.MEMBER -> listOf(GroupList.MEMBERS)
        GroupsPage.INVITE -> listOf(GroupList.CONTACTS)
        GroupsPage.REQUESTS -> listOf(GroupList.REQUESTS)
        GroupsPage.INVITATION -> listOf(GroupList.INVITATIONS)
        GroupsPage.CREATE, GroupsPage.DETAIL -> emptyList()
    }

    /**
     * Reads the header of the selected group (where the page needs it) and the first page of every list the
     * page shows. With [listsOnly], only the lists are read again (a new filter).
     */
    private fun read(listsOnly: Boolean = false) {
        if (!active || session.status.value.phase != ClientPhase.OPEN || local.value.busy) return
        retire()
        val expected = ticket
        val generation = session.status.value.generation
        val page = local.value.page
        val group = local.value.selected
        val query = local.value.query
        local.update {
            it.copy(
                loading = true,
                problem = null,
                authorityFresh = if (listsOnly) it.authorityFresh else false,
                canManage = if (listsOnly) it.canManage else false
            )
        }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation, owner) ?: return@launch
                owner = selected.id
                observe(selected.id)
                val needsHeader = !listsOnly && group != null && page in HEADER_PAGES
                val detail = if (needsHeader) groups.detail(selected.id, requireNotNull(group)) else null
                current(expected, generation)
                val staged = page.lists().map { list ->
                    requireNotNull(feeds[list]).stage(selected.id, group, query, null, null).also { current(expected, generation) }
                }
                if (account(expected, generation, selected.id) == null) return@launch
                var failure: ClientResult<*>? = null
                when (detail) {
                    null -> Unit
                    is ClientResult.Ready -> {
                        val value = detail.value
                        val own = value.roster.filter { it.active && it.root == value.own.root && it.device == value.own.device }
                        header = value.copy(group = value.group.copy(members = emptyList()), roster = own) to value.roster.size
                    }
                    else -> {
                        header = null
                        failure = detail
                    }
                }
                for ((problem, place) in staged) {
                    place()
                    if (failure == null) failure = problem
                }
                local.update {
                    it.copy(
                        account = selected.label ?: selected.id,
                        loaded = failure == null || it.loaded,
                        pending = groups.pending(selected.id)?.operationId
                    )
                }
                publish()
                failure?.let(::failure)
                if (page == GroupsPage.INVITATION && local.value.selectedInvitation == null) {
                    local.update { it.copy(problem = uiText(R.string.groups_expired)) }
                }
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

    /** Loads the next or previous window page of one list; the other lists and the header are untouched. */
    private fun more(list: GroupList, forward: Boolean) {
        val feed = requireNotNull(feeds[list])
        val account = owner ?: return
        if (list !in local.value.page.lists() || request?.isActive == true || feed.job?.isActive == true) return
        val cursor = (if (forward) feed.window.next else feed.window.previous) ?: return
        val expected = ticket
        val generation = session.status.value.generation
        val group = local.value.selected
        val query = local.value.query
        local.update { it.copy(loadingMore = true) }
        feed.job = viewModelScope.launch {
            try {
                val (problem, place) = feed.stage(account, group, query, cursor, forward)
                current(expected, generation)
                place()
                if (problem == null) publish() else failure(problem)
            } finally {
                if (expected == ticket) local.update { it.copy(loadingMore = false) }
            }
        }
    }

    /** Projects the bounded windows and the header into screen rows (at most a few hundred rows per list). */
    private fun publish() {
        val current = header
        val detail = current?.first
        val paging = feeds.mapValues { (_, feed) -> ListPaging(feed.window.next != null, feed.window.previous != null) }
        val members = memberFeed.window.rows().map { row ->
            MemberRow(
                row.id,
                row.principal,
                row.root,
                row.device,
                row.role.label(),
                row.active,
                detail?.canChange(row) == true,
                row.role == GroupRole.ADMIN,
                row.name,
                row.ai
            )
        }.toPersistentList()
        val invitations = inviteFeed.window.rows().map { row ->
            InviteRow(row.id, row.name ?: row.id, row.creator, row.inviter, UiText.DateTime(row.expiresAt))
        }.toPersistentList()
        local.update { screen ->
            screen.copy(
                groups = groupFeed.window.rows().map { row ->
                    GroupRow(row.id, row.name ?: row.id, UiText.Quantity(R.plurals.groups_member_count, row.members.size.toULong()))
                }.toPersistentList(),
                invitations = invitations,
                members = members,
                contacts = contactFeed.window.rows().filter { it.root != null }.map { row ->
                    GroupRow(row.id, row.name ?: row.principal, UiText.Data(row.principal))
                }.toPersistentList(),
                requests = requestFeed.window.rows().map { row ->
                    RequestRow(row.id, row.status.requestLabel(row.waiting), UiText.DateTime(row.expiresAt))
                }.toPersistentList(),
                paging = paging.toPersistentMap(),
                title = detail?.let { it.group.name ?: it.group.id } ?: screen.title,
                epoch = detail?.group?.epoch?.let { value -> UiText.Unsigned(value) } ?: screen.epoch,
                memberCount = current?.let { UiText.Quantity(R.plurals.groups_member_count, it.second.toULong()) } ?: screen.memberCount,
                ownRole = detail?.ownRole?.label() ?: screen.ownRole,
                canManage = detail?.canManage == true,
                canLeave = detail?.canLeave == true,
                authorityFresh = detail != null,
                selectedMember = screen.selectedMember?.let { selected ->
                    members.find { it.id == selected.id } ?: selected.copy(manageable = false)
                },
                selectedInvitation = invitations.find { it.id == screen.selected }.takeIf { screen.page == GroupsPage.INVITATION }
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
            if (change.operation == GroupOperation.REMOVE) add(uiText(R.string.groups_remove_explanation))
            if (change.operation == GroupOperation.LEAVE) add(uiText(R.string.groups_leave_explanation))
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
        retire()
        val expected = ticket
        val generation = session.status.value.generation
        busyOwner = expected
        local.update { it.copy(busy = true, confirmation = null, problem = null) }
        request = viewModelScope.launch {
            try {
                if (account(expected, generation, accountId) == null) return@launch
                clientDebugEvent("groups.mutate.start:" + attempt.second.operation)
                val result = groups.mutate(accountId, attempt.second, attempt.first)
                clientDebugEvent(
                    "groups.mutate.done:" + attempt.second.operation + ":" +
                        (if (result is ClientResult.Ready) "ready" else (result as? ClientResult.Failed)?.problem?.code ?: "unavailable")
                )
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
                            clientDebugEvent("groups.create.acknowledged")
                            // The creation reply is the group's metadata and names the conversation the SDK bound to it:
                            // open it without further reads, which could run before the group list caught up.
                            result.value.conversation?.let { conversation ->
                                clientDebugEvent("groups.create.open_chat_from_reply")
                                channel.send(GroupsEvent.OpenChat(accountId, conversation, result.value.group))
                                return@launch
                            }
                            val readback = groups.detail(accountId, result.value.group)
                            current(expected, generation)
                            if (readback !is ClientResult.Ready) {
                                clientDebugEvent("groups.create.readback_failed")
                                failure(readback)
                                return@launch
                            }
                            if (readback.value.group.id != result.value.group || !readback.value.own.authorized ||
                                readback.value.ownRole != GroupRole.OWNER
                            ) {
                                clientDebugEvent("groups.create.access_changed")
                                local.update { it.copy(problem = uiText(R.string.groups_access_changed)) }
                                return@launch
                            }
                            val destination = groups.conversation(accountId, result.value.group)
                            current(expected, generation)
                            if (account(expected, generation, accountId) == null) {
                                clientDebugEvent("groups.create.account_changed")
                                return@launch
                            }
                            when (destination) {
                                is ClientResult.Ready -> {
                                    clientDebugEvent("groups.create.open_chat")
                                    channel.send(GroupsEvent.OpenChat(accountId, destination.value, result.value.group))
                                    return@launch
                                }
                                else -> {
                                    clientDebugEvent("groups.create.conversation_failed")
                                    failure(destination)
                                }
                            }
                        } else {
                            // After leaving, the group is gone from this phone's list (or waits for the owner's device).
                            if (attempt.second.operation == GroupOperation.LEAVE) {
                                local.update { it.copy(page = GroupsPage.LIST, selected = null, selectedMember = null) }
                            }
                            if (attempt.second.operation == GroupOperation.REMOVE && !result.value.waiting) {
                                attempt.second.member?.let { memberFeed.window.remove(it.id) }
                                local.update { it.copy(page = GroupsPage.MEMBERS, selectedMember = null) }
                            }
                            // An added contact is shown where it now belongs: the member list, reloaded below.
                            if (attempt.second.operation == GroupOperation.ADD && !result.value.waiting) {
                                local.update { it.copy(page = GroupsPage.MEMBERS) }
                            }
                            needsRefresh = true
                        }
                    }
                    else -> failure(result)
                }
            } catch (cancelled: CancellationException) {
                clientDebugEvent("groups.mutate.retired:" + (expected != ticket))
                throw cancelled
            } finally {
                prepared = null
                if (busyOwner == expected) {
                    busyOwner = 0L
                    local.update { it.copy(busy = false) }
                }
                if (expected == ticket) {
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
        retire()
        val expected = ticket
        val generation = session.status.value.generation
        request = viewModelScope.launch {
            if (account(expected, generation, owner) == null) return@launch
            val result = groups.conversation(owner, group)
            current(expected, generation)
            if (account(expected, generation, owner) == null) return@launch
            when (result) {
                is ClientResult.Ready -> channel.send(GroupsEvent.OpenChat(owner, result.value, group))
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

    private companion object {
        /** Six repository pages of 100 rows: at most 600 rows held per list. */
        const val WINDOW_PAGES = 6
        const val MAX_QUERY = 256
        const val QUERY_DEBOUNCE_MS = 250L
        val HEADER_PAGES = setOf(GroupsPage.DETAIL, GroupsPage.MEMBERS, GroupsPage.MEMBER, GroupsPage.INVITE, GroupsPage.REQUESTS)
    }
}

/** Where Back leads; null leaves the groups screen. */
private fun GroupsPage.parent(): GroupsPage? = when (this) {
    GroupsPage.LIST -> null
    GroupsPage.CREATE, GroupsPage.DETAIL, GroupsPage.INVITATION -> GroupsPage.LIST
    GroupsPage.MEMBERS, GroupsPage.REQUESTS -> GroupsPage.DETAIL
    GroupsPage.MEMBER, GroupsPage.INVITE -> GroupsPage.MEMBERS
}
private fun ClientProblem.message() = when (reason) {
    ClientFailureReason.GROUP_CAPACITY -> uiText(R.string.groups_capacity)
    ClientFailureReason.GROUP_INVALID -> uiText(R.string.groups_invalid_request)
    null -> if (outcome == ClientOutcome.UNKNOWN) {
        uiText(R.string.groups_unknown, failureDetail(code, statusName, check))
    } else {
        failureText(R.string.groups_error_code, code, statusName, check)
    }
}
private fun GroupOperation.label() = uiText(
    when (this) {
        GroupOperation.CREATE -> R.string.groups_create
        GroupOperation.RENAME -> R.string.groups_rename
        GroupOperation.ADD -> R.string.groups_add
        GroupOperation.REMOVE -> R.string.groups_remove
        GroupOperation.ROLE -> R.string.groups_role
        GroupOperation.ACCEPT -> R.string.groups_accept
        GroupOperation.DECLINE -> R.string.groups_decline
        GroupOperation.LEAVE -> R.string.groups_leave
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
        this == "already_applied" -> R.string.groups_already_applied
        this == "capacity" -> R.string.groups_capacity
        this == "invalid" -> R.string.groups_invalid_request
        this == "expired" -> R.string.groups_expired
        this in setOf("permission_denied", "rejected", "refused") -> R.string.groups_refused
        else -> R.string.groups_unknown_request
    }
)
