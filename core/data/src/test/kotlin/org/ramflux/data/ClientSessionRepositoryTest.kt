// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClientSessionRepositoryTest {
    @Test fun queuedBackgroundWaitsForStartupAndForConfirmedShutdown() = runTest(timeout = 5.seconds) {
        val opened = CompletableDeferred<Unit>(); val closed = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> { calls.add("open"); opened.await(); return ClientResult.Ready(Unit) }
            override suspend fun close(): ClientResult<Unit> { calls.add("close"); closed.await(); return ClientResult.Ready(Unit) }
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        val foreground = launch { repository.foreground() }; runCurrent()
        val background = launch { repository.background() }; runCurrent()
        assertEquals(listOf("open"), calls)
        opened.complete(Unit); runCurrent()
        assertEquals(listOf("open", "close"), calls); assertEquals(ClientPhase.CLOSING, repository.status.value.phase)
        assertFalse(background.isCompleted)
        closed.complete(Unit); foreground.join(); background.join()
        assertEquals(ClientPhase.CLOSED, repository.status.value.phase)
    }
    @Test fun pendingShutdownIsNotReportedClosedOrAvailable() = runTest(timeout = 5.seconds) {
        val problem = ClientProblem(ClientFailureKind.SHUTDOWN_PENDING, 24, ClientOutcome.UNKNOWN)
        val driver = object : ClientLifecycleDriver {
            override suspend fun open(): ClientResult<Unit> = ClientResult.Ready(Unit)
            override suspend fun close(): ClientResult<Unit> = ClientResult.Failed(problem)
        }
        val repository = DefaultClientSessionRepository(driver, StandardTestDispatcher(testScheduler))
        repository.foreground(); repository.background()
        assertEquals(ClientPhase.FAILED, repository.status.value.phase); assertEquals(problem, repository.status.value.problem)
        assertTrue(repository.availability(ClientFeature.ONBOARDING) is ClientResult.Failed)
    }
}
