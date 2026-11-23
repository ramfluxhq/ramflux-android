// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.AccountAccessRepository
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.MediaRepository
import org.ramflux.data.MessageHistoryRepository

/** One conversation's decrypted media; [title] is peer-controlled and empty when the directory has none. */
@Immutable data class StorageRow(val conversation: String, val title: String, val bytes: Long) {
    override fun toString() = "StorageRow(<redacted>)"
}

@Immutable data class MediaStorageState(
    val loading: Boolean = true,
    val total: Long = 0,
    val outbox: Long = 0,
    val rows: ImmutableList<StorageRow> = persistentListOf(),
    /** The conversation whose media the person is asked to confirm clearing; "" means every conversation. */
    val confirm: String? = null,
    val closed: Boolean = false
) {
    override fun toString() = "MediaStorageState(<redacted>)"
}

sealed interface MediaStorageAction {
    data class Load(val accountId: String) : MediaStorageAction
    data object Refresh : MediaStorageAction
    data class AskClear(val conversation: String?) : MediaStorageAction
    data object ConfirmClear : MediaStorageAction
    data object Dismiss : MediaStorageAction
}

/**
 * Storage management (media design 5.7, board B30 to be drawn): bytes of decrypted media by conversation
 * as the client measures its own store (the SDK has no storage report yet, request R9), clearing with
 * confirmation, and the retention policy applied on every load.
 */
@HiltViewModel
class MediaStorageViewModel @Inject constructor(
    private val media: MediaRepository,
    private val history: MessageHistoryRepository,
    private val accounts: AccountAccessRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private var account: String? = null
    private var job: Job? = null
    private val local = MutableStateFlow(MediaStorageState())
    val state = combine(local, session.status) { screen, status ->
        if (status.phase == ClientPhase.OPEN) screen else MediaStorageState(loading = false, closed = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), MediaStorageState())

    fun onAction(action: MediaStorageAction) {
        when (action) {
            is MediaStorageAction.Load -> if (account != action.accountId) {
                account = action.accountId
                load()
            }
            MediaStorageAction.Refresh -> load()
            is MediaStorageAction.AskClear -> local.update { it.copy(confirm = action.conversation ?: "") }
            MediaStorageAction.Dismiss -> local.update { it.copy(confirm = null) }
            MediaStorageAction.ConfirmClear -> clear()
        }
    }

    private fun load() {
        val id = account ?: return
        if (session.status.value.phase != ClientPhase.OPEN) return
        job?.cancel()
        local.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            if (!active(id)) return@launch
            media.evict(id, System.currentTimeMillis())
            val storage = media.storage(id)
            val titles = (history.conversationTitles(id, storage.conversations.mapTo(HashSet()) { it.conversation }) as? ClientResult.Ready)
                ?.value.orEmpty()
            if (account != id) return@launch
            local.update {
                it.copy(
                    loading = false,
                    total = storage.total,
                    outbox = storage.outbox,
                    rows = storage.conversations.map { row -> StorageRow(row.conversation, titles[row.conversation].orEmpty(), row.bytes) }
                        .toPersistentList()
                )
            }
        }
    }

    private fun clear() {
        val id = account ?: return
        val target = local.value.confirm ?: return
        local.update { it.copy(confirm = null, loading = true) }
        viewModelScope.launch {
            if (!active(id)) return@launch
            if (target.isEmpty()) {
                local.value.rows.forEach { media.purgeConversation(id, it.conversation) }
            } else {
                media.purgeConversation(id, target)
            }
            load()
        }
    }

    private suspend fun active(id: String): Boolean {
        val catalog = accounts.list() as? ClientResult.Ready ?: return false
        return catalog.value.activeId == id && catalog.value.accounts.any { it.id == id && it.unlocked }
    }
}
