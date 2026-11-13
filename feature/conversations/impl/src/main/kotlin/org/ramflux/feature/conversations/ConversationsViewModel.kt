// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*

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
        if (status.phase == ClientPhase.OPEN) value else ConversationsState(problem = "Private data is closed.")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), ConversationsState())
    private var request: Job? = null
    private var cursor: String? = null
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect { (phase, _) ->
            request?.cancel()
            ticket++
            cursor = null
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
                    channel.send(
                        if (action == ConversationsAction.Back) ConversationsEvent.Back else ConversationsEvent.SetupAccount
                    )
                }
            }
            ConversationsAction.Refresh -> if (request?.isActive != true) read(false)
            ConversationsAction.LoadMore -> if (request?.isActive != true && cursor != null) read(true)
            is ConversationsAction.Open -> if (!local.value.loading && local.value.rows.any { it.id == action.id }) open(action.id)
        }
    }
    private fun read(more: Boolean) {
        if (session.status.value.phase != ClientPhase.OPEN) return
        val expected = ++ticket
        val generation = session.status.value.generation
        val previous = local.value
        if (!more) {
            cursor = null
            local.value = ConversationsState(loading = true)
        } else {
            local.update { it.copy(loading = true, problem = null, unavailable = null) }
        }
        request = viewModelScope.launch {
            try {
                val catalog = accounts.list()
                current(expected, generation)
                val active = (catalog as? ClientResult.Ready)?.value
                val account = active?.activeId?.takeIf { id -> active.accounts.any { it.id == id && it.unlocked } }
                if (account == null || (more && account != previous.accountId)) {
                    cursor = null
                    local.value = ConversationsState(
                        accountRequired = catalog is ClientResult.Ready,
                        problem = if (catalog is ClientResult.Failed) "Account read failed (code ${catalog.problem.code})." else null
                    )
                    return@launch
                }
                val result = directory.page(account, if (more) cursor else null)
                current(expected, generation)
                val now = accounts.list()
                current(expected, generation)
                if ((now as? ClientResult.Ready)?.value?.let {
                        it.activeId == account && it.accounts.any { row -> row.id == account && row.unlocked }
                    } != true
                ) {
                    cursor = null
                    local.value = ConversationsState(accountRequired = true)
                    return@launch
                }
                when (result) {
                    is ClientResult.Ready -> {
                        val rows = result.value.rows.map { row ->
                            ConversationItem(
                                row.id, row.title,
                                if (row.archived) "Archived" else "Conversation", row.unread.toString(), row.unread > 0u
                            )
                        }
                        val combined = if (more) previous.rows + rows else rows
                        if (combined.size > 1000 || combined.map { it.id }.distinct().size != combined.size ||
                            (result.value.nextCursor != null && result.value.nextCursor == cursor)
                        ) {
                            local.update { it.copy(problem = "Page conflict. Refresh the list.", hasMore = false) }
                            cursor = null
                        } else {
                            cursor = result.value.nextCursor
                            val search = directory.searchAvailability()
                            current(expected, generation)
                            local.value = ConversationsState(
                                accountId = account, rows = combined.toPersistentList(), loaded = true,
                                hasMore = cursor != null, searchAvailable = search is ClientResult.Ready,
                                unavailable = if (search is ClientResult.Unavailable) "Local search is unavailable." else null
                            )
                        }
                    }
                    is ClientResult.Failed -> local.update { it.copy(problem = "Read failed (code ${result.problem.code}).") }
                    is ClientResult.Unavailable -> local.update { it.copy(unavailable = "Conversations are unavailable.") }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private fun open(id: String) {
        val account = local.value.accountId ?: return
        val expected = ++ticket
        val generation = session.status.value.generation
        request?.cancel()
        request = viewModelScope.launch {
            val current = accounts.list()
            current(expected, generation)
            if ((current as? ClientResult.Ready)?.value?.let {
                    it.activeId == account && it.accounts.any { row -> row.id == account && row.unlocked }
                } != true
            ) {
                local.value = ConversationsState(accountRequired = true)
                return@launch
            }
            when (val result = directory.openAvailability(account, id)) {
                is ClientResult.Ready -> {
                    current(expected, generation)
                    channel.send(ConversationsEvent.OpenChat(account, id))
                }
                is ClientResult.Unavailable -> {
                    current(expected, generation)
                    local.update { it.copy(unavailable = "Chat is not included in this build yet.") }
                }
                is ClientResult.Failed -> {
                    current(expected, generation)
                    local.update { it.copy(problem = "This conversation could not be opened (code ${result.problem.code}).") }
                }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != expected || session.status.value.phase != ClientPhase.OPEN || session.status.value.generation != generation) {
            throw CancellationException(
                "retired session"
            )
        }
    }
}
