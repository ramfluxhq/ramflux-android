// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one attended account-code request that waits for the person: the sheet that confirms a change which weakens the
 * app lock. The core asks through the SDK callback, [ask] suspends until the sheet answers, and the answer is wiped
 * after use. Only a sheet on screen can answer; background and a closed session refuse it through [refuse].
 */
object AttendedAccountCode {
    /** How long the sheet waits for the code. The SDK callback gives up at the same bound. */
    const val WAIT_MS = 60_000L

    private val open = MutableStateFlow(false)
    private val pending = MutableStateFlow<CompletableDeferred<CharArray?>?>(null)

    /** True while a request waits for the sheet to answer. */
    val waiting: StateFlow<Boolean> = open.asStateFlow()

    /** True when the last request ended because the sheet did not answer within [WAIT_MS]. */
    @Volatile
    var timedOut: Boolean = false
        private set

    /** Waits for the typed code. Null means refused, closed or timed out. The caller erases a returned array. */
    suspend fun ask(): CharArray? {
        val answer = CompletableDeferred<CharArray?>()
        timedOut = false
        pending.value = answer
        open.value = true
        try {
            return try {
                withTimeout(WAIT_MS) { answer.await() }
            } catch (_: TimeoutCancellationException) {
                timedOut = true
                null
            }
        } finally {
            pending.compareAndSet(answer, null)
            open.value = false
            answer.complete(null)
        }
    }

    /** Hands the typed [code] to the waiting request. It is wiped here when no request waits. */
    fun submit(code: CharArray) {
        val answer = pending.value
        if (answer == null || !answer.complete(code)) code.fill('\u0000')
    }

    /** Refuses the waiting request, if any, so the core answers `reauth_required`. */
    fun refuse() {
        pending.value?.complete(null)
    }

    /**
     * Refuses the waiting request and waits, at most [timeoutMs], until its sheet has closed. The background path calls
     * this before the client suspends, so a prompt is not left mid-flight. Returns false when the wait ran out.
     */
    suspend fun refuseAndAwait(timeoutMs: Long): Boolean {
        refuse()
        return withTimeoutOrNull(timeoutMs) { waiting.first { !it } } != null
    }
}
