// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DefaultClientSessionRepository(
    private val driver: ClientLifecycleDriver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ClientSessionRepository {
    private val transitions = Mutex()
    private val mutable = MutableStateFlow(ClientSnapshot())
    override val status = mutable.asStateFlow()
    // Acquire in caller order before dispatching IO, so a late foreground cannot overtake background.
    override suspend fun foreground() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                mutable.value = mutable.value.copy(phase = ClientPhase.OPENING, problem = null)
                when (val result = driver.open()) {
                    is ClientResult.Ready -> mutable.value = ClientSnapshot(ClientPhase.OPEN, mutable.value.generation + 1)
                    is ClientResult.Failed -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED, problem = result.problem)
                    is ClientResult.Unavailable -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED,
                        problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                }
            }
        }
    }
    override suspend fun background() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                mutable.value = mutable.value.copy(phase = ClientPhase.CLOSING, problem = null)
                when (val result = driver.close()) {
                    is ClientResult.Failed -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED, problem = result.problem)
                    is ClientResult.Ready -> mutable.value = mutable.value.copy(phase = ClientPhase.CLOSED)
                    is ClientResult.Unavailable -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED,
                        problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                }
            }
        }
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> =
        if (status.value.phase != ClientPhase.OPEN) ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 9, ClientOutcome.NONE))
        else if (feature == ClientFeature.ONBOARDING) ClientResult.Ready(Unit)
        else ClientResult.Unavailable(feature)
}
