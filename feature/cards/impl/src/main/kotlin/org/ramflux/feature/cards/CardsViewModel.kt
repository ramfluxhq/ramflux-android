// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.cards.impl.R
@HiltViewModel
class CardsViewModel @Inject constructor(
    private val cards: CardHostAvailabilityRepository,
    private val session: ClientSessionRepository
) : ViewModel() {
    private val local = MutableStateFlow(CardsState())
    private val channel = Channel<CardsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val state = combine(local, session.status) { value, status ->
        if (status.phase == ClientPhase.OPEN) value else CardsState(closed = true, unavailable = null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), CardsState())
    private var visible = false
    private var request: Job? = null
    private var ticket = 0L
    private val lifecycle = viewModelScope.launch {
        session.status.map { it.phase to it.generation }.distinctUntilChanged().collect {
            retire()
            local.value = CardsState()
            if (visible && it.first == ClientPhase.OPEN) check()
        }
    }
    fun onAction(action: CardsAction) {
        when (action) {
            is CardsAction.Visible -> {
                visible = action.value
                if (visible) {
                    check()
                } else {
                    retire()
                    local.value = CardsState()
                }
            }
            CardsAction.Refresh -> if (request?.isActive != true) check()
            CardsAction.Back -> {
                visible = false
                retire()
                local.value = CardsState()
                viewModelScope.launch { channel.send(CardsEvent.Back) }
            }
        }
    }
    private fun retire() {
        request?.cancel()
        ticket++
    }
    private fun check() {
        if (!visible || session.status.value.phase != ClientPhase.OPEN) return
        retire()
        val expected = ticket
        val generation = session.status.value.generation
        local.value = CardsState(checking = true, canCheck = true, unavailable = null)
        request = viewModelScope.launch {
            try {
                val result = cards.availability()
                currentCoroutineContext().ensureActive()
                if (!visible || ticket != expected || session.status.value.phase != ClientPhase.OPEN ||
                    session.status.value.generation != generation
                ) {
                    return@launch
                }
                local.value = when (result) {
                    is ClientResult.Unavailable -> CardsState(
                        canCheck = true,
                        unavailable = uiText(
                            when (result.reason) {
                                UnavailableReason.FEATURE_NOT_INSTALLED -> R.string.cards_missing
                                UnavailableReason.UNSUPPORTED_BY_CORE -> R.string.cards_unsupported
                                UnavailableReason.CUSTODY_PROVIDER_REQUIRED -> R.string.cards_access
                            }
                        )
                    )
                    is ClientResult.Failed -> CardsState(
                        canCheck = true, unavailable = null,
                        problem = uiText(R.string.cards_error_code, UiText.Number(result.problem.code.toLong()))
                    )
                    // This module has no verified runtime/session contract; an unexpected Ready must not open content.
                    is ClientResult.Ready -> CardsState(canCheck = true, unavailable = null, problem = uiText(R.string.cards_unverified))
                }
            } finally {
                if (ticket == expected) local.update { it.copy(checking = false) }
            }
        }
    }
}
