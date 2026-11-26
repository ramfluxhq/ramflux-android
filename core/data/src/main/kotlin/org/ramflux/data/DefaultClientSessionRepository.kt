// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ramflux.data.cards.CardLifecycleHooks

internal class DefaultClientSessionRepository(
    private val driver: ClientLifecycleDriver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Cards are suspended before the client goes to the background or the app lock engages, and resumed after. */
    private val cards: CardLifecycleHooks = NoCardLifecycle,
) : ClientSessionRepository {
    private val transitions = Mutex()
    private val mutable = MutableStateFlow(ClientSnapshot())
    /** The lock covered the screens since the last release: their reads failed behind the cover and must be redone. */
    @Volatile private var lockEngaged = false
    override val status = mutable.asStateFlow()

    init {
        // A refusal that names the app lock covers the screens at once; the next read decides what follows.
        driver.onAppLockRefused {
            mutable.update { if (it.phase == ClientPhase.OPEN) it.copy(appLock = AppLockPhase.LOCKED) else it }
            lockEngaged = true
        }
    }

    // Acquire in caller order before dispatching IO, so a late foreground cannot overtake background.
    override suspend fun foreground() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                val current = mutable.value
                if (current.phase == ClientPhase.OPEN && current.suspended) {
                    // Return from the background: the same client resumes; no new generation, no account code.
                    mutable.value = current.copy(appLock = AppLockPhase.UNKNOWN)
                    val held = holdsLock(current)
                    mutable.value = when (val result = driver.open()) {
                        is ClientResult.Ready -> current.copy(appLock = AppLockPhase.UNKNOWN, suspended = false, problem = null)
                        is ClientResult.Failed -> current.copy(phase = ClientPhase.FAILED, problem = result.problem, suspended = false, lockHeld = held)
                        is ClientResult.Unavailable -> current.copy(phase = ClientPhase.FAILED, suspended = false, lockHeld = held,
                            problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                    }
                    if (mutable.value.phase == ClientPhase.OPEN) readAppLock()
                    if (mutable.value.phase == ClientPhase.OPEN) cards.foreground()
                    clientDebugEvent("session.resume:" + mutable.value.phase)
                    return@withContext
                }
                mutable.value = current.copy(phase = ClientPhase.OPENING, problem = null, suspended = false)
                when (val result = driver.open()) {
                    is ClientResult.Ready -> {
                        mutable.value = ClientSnapshot(ClientPhase.OPEN, mutable.value.generation + 1)
                        clientDebugEvent("session.open")
                        readAppLock()
                        if (mutable.value.phase == ClientPhase.OPEN) cards.foreground()
                    }
                    is ClientResult.Failed -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED, problem = result.problem)
                    is ClientResult.Unavailable -> mutable.value = mutable.value.copy(phase = ClientPhase.FAILED,
                        problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                }
            }
        }
    }
    /**
     * Background: the cover rises first and stays until the next foreground read. The open client then suspends
     * (files and network released, accounts kept unlocked); nothing else changes.
     */
    override suspend fun background() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                val current = mutable.value
                if (current.phase != ClientPhase.OPEN || current.suspended) return@withContext
                val held = holdsLock(current)
                mutable.value = current.copy(appLock = AppLockPhase.UNKNOWN)
                cards.background()
                // Best effort: the SDK arms the deadline on suspend as well.
                driver.appLockLifecycle(APP_LOCK_BACKGROUND)
                val suspended = driver.suspend()
                mutable.update {
                    when (suspended) {
                        is ClientResult.Ready -> it.copy(suspended = true)
                        is ClientResult.Failed -> it.copy(phase = ClientPhase.FAILED, problem = suspended.problem, lockHeld = held)
                        is ClientResult.Unavailable -> it.copy(phase = ClientPhase.FAILED, lockHeld = held,
                            problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                    }
                }
                clientDebugEvent("session.suspend:" + (if (mutable.value.suspended) "ok" else "failed." + mutable.value.problem?.code))
            }
        }
    }
    /** Whether the lock was on, engaged or unread before the session left OPEN: a later failure keeps the cover up. */
    private fun holdsLock(current: ClientSnapshot): Boolean =
        current.appLockEnabled || lockEngaged || current.appLock != AppLockPhase.OPEN

    override suspend fun recheckAppLock() {
        transitions.withLock {
            withContext(io + NonCancellable) {
                // A suspended client cannot answer; the next foreground reads the lock again.
                if (mutable.value.phase == ClientPhase.OPEN && !mutable.value.suspended) readAppLock()
            }
        }
    }
    /**
     * Reads the app lock of the open account. Only an unlocked account read is followed by the foreground lifecycle call, and
     * the state that reply carries decides the phase; a failed call, or any state that is not readable, leaves the cover up.
     */
    private suspend fun readAppLock() {
        val reading = driver.appLockRead()
        val phase = when (reading.phase) {
            AppLockPhase.OPEN ->
                if (reading.foreground) driver.appLockLifecycle(APP_LOCK_FOREGROUND).phase(reading.enabled) else AppLockPhase.OPEN
            else -> reading.phase
        }
        if (phase == AppLockPhase.LOCKED) {
            lockEngaged = true
            cards.background()
        }
        // The lock closed the account in the SDK, so every screen read behind the cover failed or saw no active account.
        // The release is a new generation: open screens clear and read again, and the navigation stack stays as it was.
        val released = phase == AppLockPhase.OPEN && lockEngaged
        if (released) lockEngaged = false
        if (released) cards.foreground()
        mutable.update {
            if (it.phase == ClientPhase.OPEN) {
                it.copy(
                    appLock = phase,
                    appLockEnabled = reading.enabled,
                    appLockAccount = reading.account.takeIf { phase == AppLockPhase.LOCKED },
                    generation = if (released) it.generation + 1 else it.generation,
                )
            } else {
                it
            }
        }
        clientDebugEvent("session.app_lock:$phase")
    }
    override suspend fun availability(feature: ClientFeature): ClientResult<Unit> =
        if (status.value.phase != ClientPhase.OPEN) ClientResult.Failed(ClientProblem(ClientFailureKind.UNAVAILABLE, 9, ClientOutcome.NONE))
        else if (feature in setOf(ClientFeature.ONBOARDING, ClientFeature.CONVERSATIONS, ClientFeature.SETTINGS, ClientFeature.CONTACTS, ClientFeature.GROUPS, ClientFeature.CALLS, ClientFeature.AGENTS, ClientFeature.TASKS, ClientFeature.CARDS)) ClientResult.Ready(Unit)
        else ClientResult.Unavailable(feature)
}

/** Hosts without card sessions: nothing to suspend or resume. */
private object NoCardLifecycle : CardLifecycleHooks {
    override suspend fun background() = Unit
    override suspend fun foreground() = Unit
}
