// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
                    channel.send(if (action == ConversationsAction.Back) ConversationsEvent.Back else ConversationsEvent.SetupAccount)
                }
            }
            ConversationsAction.Refresh -> if (request?.isActive != true) read(false)
            ConversationsAction.LoadMore -> if (request?.isActive != true && cursor != null) read(true)
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
                local.value = result.notice(ConversationsState(), "Account state")
                return null
            }
            is ClientResult.Unavailable -> {
                cursor = null
                local.value = result.notice(ConversationsState(), "Account state")
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
                    local.value = ConversationsState(problem = "The active account changed. Refresh the list.")
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
            local.value = ConversationsState(loading = true)
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
                        val rows = result.value.rows.map { row ->
                            ConversationItem(
                                row.id, row.title,
                                if (row.archived) "Archived" else "Conversation", row.unread.toString(), row.isUnread,
                                row.lastActivity.displayTime()
                            )
                        }
                        val combined = if (more) previous.rows + rows else rows
                        if (combined.size > 1000 || combined.map { it.id }.distinct().size != combined.size ||
                            (result.value.nextCursor != null && result.value.nextCursor == cursor)
                        ) {
                            local.update { it.copy(problem = "Page conflict. Refresh the list.", hasMore = false) }
                            cursor = null
                            return@launch
                        }
                        cursor = result.value.nextCursor
                        val search = directory.searchAvailability()
                        current(expected, generation)
                        val afterCapabilities = account(expected, generation, selected.id) ?: return@launch
                        val fresh = ConversationsState(
                            accountId = selected.id, accountLabel = afterCapabilities.label ?: afterCapabilities.id,
                            rows = combined.toPersistentList(), loaded = true, hasMore = cursor != null
                        )
                        local.value = when (search) {
                            is ClientResult.Ready -> fresh.copy(searchAvailable = true)
                            is ClientResult.Failed -> search.notice(fresh, "Search availability")
                            is ClientResult.Unavailable -> search.notice(fresh, "Local search")
                        }
                    }
                    is ClientResult.Failed -> local.update { result.notice(it, "Conversations") }
                    is ClientResult.Unavailable -> local.update { result.notice(it, "Conversations") }
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
                    is ClientResult.Ready -> channel.send(ConversationsEvent.OpenChat(accountId, id))
                    is ClientResult.Unavailable -> local.update { result.notice(it, "Chat") }
                    is ClientResult.Failed -> local.update { result.notice(it, "Chat") }
                }
            } finally {
                if (expected == ticket) local.update { it.copy(loading = false) }
            }
        }
    }
    private suspend fun current(expected: Long, generation: Long) {
        currentCoroutineContext().ensureActive()
        if (ticket != expected || session.status.value.phase != ClientPhase.OPEN || session.status.value.generation != generation) {
            throw CancellationException("retired session")
        }
    }
}
private fun ClientResult.Failed.notice(state: ConversationsState, subject: String) = state.copy(
    problem = "$subject could not be read (code ${problem.code}).",
    problemKind = problem.kind,
    unavailable = null,
    unavailableReason = null
)
private fun ClientResult.Unavailable.notice(state: ConversationsState, subject: String) = state.copy(
    unavailable = when (reason) {
        UnavailableReason.FEATURE_NOT_INSTALLED -> "$subject is not included in this build yet."
        UnavailableReason.UNSUPPORTED_BY_CORE -> "$subject is not available in this version."
        UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> "$subject requires secure account access on this device."
    },
    unavailableReason = reason,
    problem = null,
    problemKind = null
)
private fun Long?.displayTime(): String {
    if (this == null) return ""
    return try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(this))
    } catch (_: java.time.DateTimeException) {
        "Time unavailable"
    }
}
