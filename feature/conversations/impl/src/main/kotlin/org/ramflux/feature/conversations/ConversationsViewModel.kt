// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.conversations.impl.R

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val directory: ConversationDirectoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(ConversationsState())
    private val channel = Channel<ConversationsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) value else ConversationsState(problem = uiText(R.string.ui_private_data_is_closed))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ConversationsState())
    private var request: Job? = null
    private var cursor: String? = null
    private var ticket = 0L

    /** Merging, sorting and row projection run here, never on the main thread. */
    @VisibleForTesting var work: CoroutineDispatcher = Dispatchers.Default
    private val window = ConversationWindow()
    private val windowLock = Mutex()
    private var visible = false
    private var watch: Job? = null
    private var watchedAccount: String? = null
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            stopWatch()
            ticket++
            cursor = null
            windowLock.withLock { window.clear() }
            local.value = ConversationsState()
            if (phase == ClientPhase.OPEN) onAction(ConversationsAction.Refresh)
        }
    }
    fun onAction(action: ConversationsAction) {
        when (action) {
            ConversationsAction.Back, ConversationsAction.SetupAccount -> {
                request?.cancel()
                ticket++
                local.update { it.copy(loading = false) }
                viewModelScope.launch {
                    channel.send(if (action == ConversationsAction.Back) ConversationsEvent.Back else ConversationsEvent.SetupAccount)
                }
            }
            ConversationsAction.Refresh -> if (request?.isActive != true) read(false)
            ConversationsAction.LoadMore -> if (request?.isActive != true && cursor != null) read(true)
            ConversationsAction.Compose -> viewModelScope.launch { channel.send(ConversationsEvent.Compose) }
            ConversationsAction.OpenRequests -> viewModelScope.launch { channel.send(ConversationsEvent.Requests) }
            is ConversationsAction.Visible -> {
                visible = action.visible
                if (visible) local.value.accountId?.takeIf { local.value.loaded }?.let(::startWatch) else stopWatch()
            }
            is ConversationsAction.Open -> if (request?.isActive != true && local.value.rows.any { it.id == action.id }) open(action.id)
        }
    }

    /** Account absence is asserted only by a successful authoritative catalog. */
    private suspend fun account(expected: Long, generation: Long, requiredId: String? = null): AccountSummary? {
        val result = accounts.list()
        current(expected, generation)
        when (result) {
            is ClientResult.Failed -> {
                cursor = null
                local.value = result.notice(ConversationsState(), uiText(R.string.ui_account_state))
                return null
            }
            is ClientResult.Unavailable -> {
                cursor = null
                local.value = result.notice(ConversationsState(), uiText(R.string.ui_account_state))
                return null
            }
            is ClientResult.Ready -> {
                val active = result.value.accounts.find { it.id == result.value.activeId && it.unlocked }
                if (active == null) {
                    cursor = null
                    local.value = ConversationsState(accountRequired = true)
                    return null
                }
                if (requiredId != null && active.id != requiredId) {
                    cursor = null
                    local.value = ConversationsState(problem = uiText(R.string.ui_the_active_account_changed_refresh_the_list))
                    return null
                }
                return active
            }
        }
    }
    private fun read(more: Boolean) {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val previous = local.value
        if (!more) {
            cursor = null
            // A loaded list stays on screen while it reloads (resume, resync); the first load shows the skeleton.
            local.value = if (previous.loaded) previous.copy(loading = true) else ConversationsState(loading = true)
        } else {
            local.update { it.copy(loading = true, problem = null, problemKind = null, unavailable = null, unavailableReason = null) }
        }
        request = viewModelScope.launch {
            try {
                val selected = account(expected, generation, if (more) previous.accountId else null) ?: return@launch
                local.update { it.copy(accountId = selected.id, accountLabel = selected.label ?: selected.id) }
                val result = directory.page(selected.id, if (more) cursor else null)
                current(expected, generation)
                account(expected, generation, selected.id) ?: return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        val next = result.value.nextCursor
                        if (result.value.rows.map { it.id }.distinct().size != result.value.rows.size || (next != null && next == cursor)) {
                            local.update { it.copy(problem = uiText(R.string.ui_page_conflict_refresh_the_list), hasMore = false) }
                            cursor = null
                            return@launch
                        }
                        val (rows, added) = withContext(work) {
                            windowLock.withLock {
                                if (!more) window.clear()
                                val kept = window.merge(result.value.rows)
                                project(window.ordered()) to kept
                            }
                        }
                        current(expected, generation)
                        cursor = next
                        val search = if (more) null else directory.searchAvailability()
                        val requests = if (more) previous.requests else directory.pendingRequests(selected.id).countOrNull()
                        current(expected, generation)
                        val fresh = ConversationsState(
                            accountId = selected.id, accountLabel = selected.label ?: selected.id,
                            rows = rows, loaded = true, hasMore = cursor != null, autoLoad = added > 0, requests = requests,
                            searchAvailable = previous.searchAvailable,
                            unavailable = if (more) previous.unavailable else null,
                            unavailableReason = if (more) previous.unavailableReason else null
                        )
                        val unsearchable = fresh.copy(searchAvailable = false)
                        local.value = when (search) {
                            null -> fresh
                            is ClientResult.Ready -> fresh.copy(searchAvailable = true)
                            is ClientResult.Failed -> search.notice(unsearchable, uiText(R.string.ui_search_availability))
                            is ClientResult.Unavailable -> search.notice(unsearchable, uiText(R.string.ui_local_search))
                        }
                        if (visible) startWatch(selected.id)
                    }
                    is ClientResult.Failed -> local.update { result.notice(it, uiText(R.string.ui_conversations)) }
                    is ClientResult.Unavailable -> local.update { result.notice(it, uiText(R.string.ui_conversations)) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun open(id: String) {
        val accountId = local.value.accountId ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        request?.cancel()
        local.update { it.copy(loading = true) }
        request = viewModelScope.launch {
            try {
                account(expected, generation, accountId) ?: return@launch
                val result = directory.openAvailability(accountId, id)
                current(expected, generation)
                account(expected, generation, accountId) ?: return@launch
                when (result) {
                    is ClientResult.Ready -> {
                        val row = local.value.rows.firstOrNull { it.id == id }
                        channel.send(ConversationsEvent.OpenChat(accountId, id, row?.shownTitle ?: id, row?.groupId))
                    }
                    is ClientResult.Unavailable -> local.update { result.notice(it, uiText(R.string.ui_chat)) }
                    is ClientResult.Failed -> local.update { result.notice(it, uiText(R.string.ui_chat)) }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }

    /** One shared subscription per visible list; cancelled when the list leaves the screen. */
    private fun startWatch(account: String) {
        if (watch?.isActive == true && watchedAccount == account) return
        stopWatch()
        watchedAccount = account
        val generation = session.status.value.generation
        watch = viewModelScope.launch {
            directory.changes(account).collect { result ->
                if (session.status.value.generation != generation || local.value.accountId != account) return@collect
                val change = (result as? ClientResult.Ready)?.value ?: return@collect
                apply(account, change)
            }
        }
    }

    private fun stopWatch() {
        watch?.cancel()
        watch = null
        watchedAccount = null
    }

    private suspend fun apply(account: String, change: ConversationChange) {
        when (change) {
            ConversationChange.Reload -> if (request?.isActive != true) read(false)
            ConversationChange.Requests -> {
                val count = directory.pendingRequests(account).countOrNull()
                if (local.value.accountId == account) local.update { it.copy(requests = count) }
            }
            is ConversationChange.Upsert, is ConversationChange.Remove -> {
                // A page read in flight replaces the window; it already includes this change or reloads.
                if (request?.isActive == true) return
                val rows = withContext(work) {
                    windowLock.withLock {
                        if (change is ConversationChange.Upsert) window.upsert(change.row, change.titled)
                        if (change is ConversationChange.Remove) window.remove(change.id)
                        project(window.ordered())
                    }
                }
                if (local.value.accountId == account && local.value.loaded) local.update { it.copy(rows = rows) }
            }
        }
    }

    /** Row projection for the screen; runs on [work]. */
    private fun project(rows: List<ConversationSummary>): ImmutableList<ConversationItem> = rows.map { row ->
        ConversationItem(
            row.id,
            row.title,
            uiText(if (row.archived) R.string.ui_archived else R.string.ui_conversation),
            row.unread.toString(),
            row.isUnread,
            row.lastActivity.displayTime(),
            muted = row.muted,
            group = row.group,
            groupId = row.groupId,
            identifier = row.titleIsIdentifier,
            ai = row.ai
        )
    }.toPersistentList()

    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != expected || session.status.value.phase != ClientPhase.OPEN || session.status.value.generation != generation) {
            throw CancellationException("retired session")
        }
    }
}
private fun ClientResult.Failed.notice(state: ConversationsState, subject: UiText) = state.copy(
    problem = uiText(R.string.ui_value_could_not_be_read_code_value, subject, UiText.Number(problem.code.toLong())),
    problemKind = problem.kind,
    unavailable = null,
    unavailableReason = null
)
private fun ClientResult.Unavailable.notice(state: ConversationsState, subject: UiText) = state.copy(
    unavailable = when (reason) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> uiText(R.string.ui_value_is_not_included_in_this_build_yet, subject)
        UnavailableReason.UNSUPPORTED_BY_CORE -> uiText(R.string.ui_value_is_not_available_in_this_version, subject)
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> uiText(R.string.ui_value_requires_secure_account_access_on_this_device, subject)
    },
    unavailableReason = reason,
    problem = null,
    problemKind = null
)
private fun ClientResult<Int>.countOrNull(): Int? = (this as? ClientResult.Ready)?.value
private fun Long?.displayTime(): UiText = if (this == null) UiText.Empty else UiText.DateTime(this)
