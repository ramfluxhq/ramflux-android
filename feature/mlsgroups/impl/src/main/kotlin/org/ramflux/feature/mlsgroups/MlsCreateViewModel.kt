// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsCreateState
import org.ramflux.data.MlsGroupSession

/**
 * The create form. One tap makes one `group.create` in the session; the group opens only with the `group_id` the call returned
 * (S6). W: a `@HiltViewModel` constructor.
 */
class MlsCreateViewModel(private val session: MlsGroupSession) : ViewModel() {
    private val account = MutableStateFlow<String?>(null)
    private val form = MutableStateFlow(MlsCreateUi())
    private val channel = Channel<MlsCreateEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()

    /** This form made a tap that is still waiting for its result; another form's created group is not opened by this one. */
    private var waiting = false
    private var lockWatch: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val remote = account.flatMapLatest { id -> if (id == null) flowOf(MlsCreateState()) else session.create(id) }

    val state: StateFlow<MlsCreateUi> = combine(form, remote) { form, remote ->
        form.copy(creating = remote.creating, nameError = remote.nameError, failure = remote.failure)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), MlsCreateUi())

    init {
        viewModelScope.launch {
            remote.collect { create ->
                val created = create.created
                val id = account.value
                if (created != null && id != null && waiting) {
                    waiting = false
                    session.createHandled(id)
                    channel.trySend(MlsCreateEvent.Created(id, created.groupId))
                }
            }
        }
    }

    fun onAction(action: MlsCreateAction) {
        val id = account.value
        when (action) {
            is MlsCreateAction.Open -> {
                account.value = action.account
                lockWatch?.cancel()
                lockWatch = dropOnLock(session, action.account) {
                    form.value = MlsCreateUi()
                    waiting = false
                }
            }
            is MlsCreateAction.Name -> form.value = form.value.copy(name = action.text)
            is MlsCreateAction.Approval -> form.value = form.value.copy(approval = action.on)
            MlsCreateAction.Submit -> if (id != null) {
                val current = form.value
                waiting = true
                session.createGroup(id, current.name, current.approval)
            }
            MlsCreateAction.Back -> channel.trySend(MlsCreateEvent.Back)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
