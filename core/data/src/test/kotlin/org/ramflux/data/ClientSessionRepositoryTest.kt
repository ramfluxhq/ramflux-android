// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.ramflux.data.cards.CardLifecycleHooks
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClientSessionRepositoryTest {
    @Test fun queuedBackgroundWaitsForStartupAndSuspendsWithoutClosing() = runTest(timeout = 5.seconds) {
        val opened = CompletableDeferred<Unit>(); val suspended = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> { calls.add("open"); opened.await(); return ClientResult.Ready(Unit) }
            override suspend fun suspend(): ClientResult<Unit> { calls.add("suspend"); suspended.await(); return ClientResult.Ready(Unit) }
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        val foreground = launch { repository.foreground() }; runCurrent()
        val background = launch { repository.background() }; runCurrent()
        assertEquals(listOf("open"), calls)
        opened.complete(Unit); runCurrent()
        assertEquals(listOf("open", "suspend"), calls)
        assertFalse(background.isCompleted)
        suspended.complete(Unit); foreground.join(); background.join()
        val generation = repository.status.value.generation
        assertEquals(ClientPhase.OPEN, repository.status.value.phase); assertTrue(repository.status.value.suspended)
        repository.foreground()
        assertEquals(listOf("open", "suspend", "open"), calls)
        assertEquals(ClientPhase.OPEN, repository.status.value.phase); assertFalse(repository.status.value.suspended)
        assertEquals(generation, repository.status.value.generation)
    }
    @Test fun pendingShutdownIsNotReportedClosedOrAvailable() = runTest(timeout = 5.seconds) {
        val problem = ClientProblem(ClientFailureKind.SHUTDOWN_PENDING, 24, ClientOutcome.UNKNOWN)
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Failed(problem)
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        repository.foreground(); repository.background()
        assertEquals(ClientPhase.FAILED, repository.status.value.phase); assertEquals(problem, repository.status.value.problem)
        assertTrue(repository.availability(ClientFeature.ONBOARDING) is ClientResult.Failed)
    }
    @Test fun releasingTheAppLockStartsANewGenerationSoOpenScreensReadAgain() = runTest(timeout = 5.seconds) {
        val readings = ArrayDeque(listOf(
            AppLockReading(AppLockPhase.LOCKED, enabled = true, account = "acct"),
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
        ))
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        repository.foreground()
        assertEquals(AppLockPhase.LOCKED, repository.status.value.appLock)
        val locked = repository.status.value.generation
        repository.recheckAppLock()
        assertEquals(AppLockPhase.OPEN, repository.status.value.appLock)
        assertEquals(locked + 1, repository.status.value.generation)
        // A later read that finds the lock open changes nothing.
        repository.recheckAppLock()
        assertEquals(locked + 1, repository.status.value.generation)
    }
    @Test fun aFailedResumeAfterALockedReadKeepsTheCoverUpAndRetryReopens() = runTest(timeout = 5.seconds) {
        val readings = ArrayDeque(listOf(
            AppLockReading(AppLockPhase.LOCKED, enabled = true, account = "acct"),
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
        ))
        var opens = 0
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> {
                opens++
                return if (opens == 2) ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE)) else ClientResult.Ready(Unit)
            }
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        repository.foreground()
        assertTrue(repository.status.value.appLockCovers())
        repository.background()
        repository.foreground()
        assertEquals(ClientPhase.FAILED, repository.status.value.phase)
        assertTrue(repository.status.value.appLockCovers())
        // The cover's retry is a full reopen; the lock read that follows releases it.
        repository.foreground()
        assertEquals(ClientPhase.OPEN, repository.status.value.phase)
        assertFalse(repository.status.value.appLockCovers())
    }
    @Test fun aFailedOpenWithoutALockShowsNoCover() = runTest(timeout = 5.seconds) {
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        repository.foreground()
        assertEquals(ClientPhase.FAILED, repository.status.value.phase)
        assertFalse(repository.status.value.appLockCovers())
    }
    @Test fun cardsStayDownBehindTheLockAndResumeOnRelease() = runTest(timeout = 5.seconds) {
        val readings = ArrayDeque(listOf(
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
            AppLockReading(AppLockPhase.LOCKED, enabled = true, account = "acct"),
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
        ))
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
        }
        val cards = RecordingCards()
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler), cards)
        cards.repository = repository
        repository.foreground()
        repository.background()
        assertEquals(listOf("background"), cards.calls)
        // Back in the foreground the lock covers the screens: the cards must not resume behind it.
        repository.foreground()
        assertEquals(AppLockPhase.LOCKED, repository.status.value.appLock)
        assertEquals(listOf("background"), cards.calls)
        repository.recheckAppLock()
        assertEquals(listOf("background", "foreground"), cards.calls)
    }
    @Test fun aLockRefusalCoversTheScreensAtOnceAndSuspendsTheCardsAfterwards() = runTest(timeout = 5.seconds) {
        var refused: (() -> Unit)? = null
        val readings = ArrayDeque(listOf(AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false)))
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
            override fun onAppLockRefused(listener: () -> Unit) {
                refused = listener
            }
        }
        val cards = RecordingCards()
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler), cards)
        cards.repository = repository
        repository.foreground()
        refused?.invoke()
        advanceUntilIdle()
        assertEquals(listOf("background"), cards.calls)
        assertEquals(AppLockPhase.LOCKED, cards.appLockAtBackground)
        assertEquals(AppLockPhase.LOCKED, repository.status.value.appLock)
    }
    @Test fun theCoverRisesImmediatelyWhileACardCallHoldsItsLock() = runTest(timeout = 5.seconds) {
        var refused: (() -> Unit)? = null
        val readings = ArrayDeque(listOf(AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false)))
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
            override fun onAppLockRefused(listener: () -> Unit) {
                refused = listener
            }
        }
        val release = CompletableDeferred<Unit>()
        val cards = object : CardLifecycleHooks {
            override suspend fun background() {
                release.await()
            }
            override suspend fun foreground() = Unit
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler), cards)
        repository.foreground()
        refused?.invoke()
        // The card suspend is stuck behind a card call; the cover is already published and no step was needed.
        assertEquals(AppLockPhase.LOCKED, repository.status.value.appLock)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(AppLockPhase.LOCKED, repository.status.value.appLock)
    }
    @Test fun aRecheckThatReadsOpenAfterAFailedReadResumesTheCards() = runTest(timeout = 5.seconds) {
        val readings = ArrayDeque(listOf(
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
            AppLockReading(AppLockPhase.UNKNOWN, enabled = true),
            AppLockReading(AppLockPhase.OPEN, enabled = true, foreground = false),
        ))
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun suspend(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun appLockRead(): AppLockReading = readings.removeFirst()
        }
        val cards = RecordingCards()
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler), cards)
        repository.foreground()
        repository.background()
        repository.foreground()
        assertEquals(listOf("background"), cards.calls)
        repository.recheckAppLock()
        assertEquals(listOf("background", "foreground"), cards.calls)
    }
}

/** Records the card lifecycle calls and the app lock phase the screens showed when the cards were suspended. */
private class RecordingCards : CardLifecycleHooks {
    val calls = mutableListOf<String>()
    var repository: DefaultClientSessionRepository? = null
    var appLockAtBackground: AppLockPhase? = null
    override suspend fun background() {
        calls += "background"
        appLockAtBackground = repository?.status?.value?.appLock
    }
    override suspend fun foreground() {
        calls += "foreground"
    }
}
