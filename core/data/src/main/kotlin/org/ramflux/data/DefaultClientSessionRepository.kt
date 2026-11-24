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
                val current = mutable.value
                if (current.phase == ClientPhase.OPEN && current.suspended) {
                    // Return from the background: the same client resumes; no new generation, no account code.
                    mutable.value = when (val result = driver.open()) {
                        is ClientResult.Ready -> current.copy(suspended = false, problem = null)
                        is ClientResult.Failed -> current.copy(phase = ClientPhase.FAILED, problem = result.problem, suspended = false)
                        is ClientResult.Unavailable -> current.copy(phase = ClientPhase.FAILED, suspended = false,
                            problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                    }
                    clientDebugEvent("session.resume:" + mutable.value.phase)
                    return@withContext
                }
                mutable.value = current.copy(phase = ClientPhase.OPENING, problem = null, suspended = false)
                when (val result = driver.open()) {
                    is ClientResult.Ready -> mutable.value = ClientSnapshot(ClientPhase.OPEN, mutable.value.generation + 1)
                        .also { clientDebugEvent("session.open") }
                    is ClientResult.Failed -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED, problem = result.problem)
                    is ClientResult.Unavailable -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED,
                        problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                }
            }
        }
    }
    /** Background: the open client suspends (files and network released, accounts kept unlocked); nothing else changes. */
    override suspend fun background() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                val current = mutable.value
                if (current.phase != ClientPhase.OPEN || current.suspended) return@withContext
                mutable.value = when (val result = driver.suspend()) {
                    is ClientResult.Ready -> current.copy(suspended = true)
                    is ClientResult.Failed -> current.copy(phase = ClientPhase.FAILED, problem = result.problem)
                    is ClientResult.Unavailable -> current.copy(phase = ClientPhase.FAILED,
                        problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                }
                clientDebugEvent("session.suspend:" + (if (mutable.value.suspended) "ok" else "failed." + mutable.value.problem?.code))
            }
        }
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> =
        if (status.value.phase != ClientPhase.OPEN) ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 9, ClientOutcome.NONE))
        else if (feature in setOf(ClientFeature.ONBOARDING, ClientFeature.CONVERSATIONS, ClientFeature.SETTINGS, ClientFeature.CONTACTS, ClientFeature.GROUPS, ClientFeature.CALLS, ClientFeature.AGENTS, ClientFeature.TASKS, ClientFeature.CARDS)) ClientResult.Ready(Unit)
        else ClientResult.Unavailable(feature)
}
