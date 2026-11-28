// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.ramflux.data.MlsCreateState
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsScreenEvents

/**
 * The create form. One tap makes one `group.create` in the session; the group opens only with the `group_id` the call returned
 * (S6). W: a `@HiltViewModel` constructor.
 */
class MlsCreateViewModel(private val session: MlsGroupSession) : ViewModel() {
    private val account = MutableStateFlow<String?>(null)
    private val form = MutableStateFlow(MlsCreateUi())
    private val channel = MlsScreenEvents<MlsCreateEvent>()
    val events = channel.flow

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
                    channel.send(MlsCreateEvent.Created(id, created.groupId))
                }
            }
        }
    }

    fun onAction(action: MlsCreateAction) {
        val id = account.value
        when (action) {
            is MlsCreateAction.Open -> {
                if (account.value != action.account) {
                    // Another account's form: the typed name, the approval choice and the waiting tap stay with the old one.
                    form.value = MlsCreateUi()
                    waiting = false
                    channel.reopened()
                }
                account.value = action.account
                lockWatch?.cancel()
                lockWatch = dropOnLock(session, action.account) {
                    // The lock or a change of account drops the form and the waiting tap, and the screen closes.
                    form.value = MlsCreateUi()
                    waiting = false
                    channel.lockBack(MlsCreateEvent.Back)
                }
            }
            is MlsCreateAction.Name -> form.value = form.value.copy(name = action.text)
            is MlsCreateAction.Approval -> form.value = form.value.copy(approval = action.on)
            MlsCreateAction.Submit -> if (id != null) {
                val current = form.value
                waiting = true
                session.createGroup(id, current.name, current.approval)
            }
            MlsCreateAction.Back -> channel.send(MlsCreateEvent.Back)
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
