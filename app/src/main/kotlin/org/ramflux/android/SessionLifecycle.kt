// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import org.ramflux.data.AttendedAccountCode
import org.ramflux.data.ClientSessionRepository

/** Application-owned lifecycle work survives Activity/configuration and ViewModel destruction. */
@Singleton
class SessionLifecycle @Inject constructor(private val session: ClientSessionRepository) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var foreground: Job? = null
    private var background: Job? = null
    override fun onStart(owner: LifecycleOwner) {
        foreground = scope.launch { session.foreground() }
    }
    override fun onStop(owner: LifecycleOwner) {
        // A pending account-code request is refused first: its sheet closes, bounded, before the client suspends.
        background = scope.launch {
            AttendedAccountCode.refuseAndAwait(REFUSAL_WAIT_MS)
            session.background()
        }
    }

    private companion object {
        const val REFUSAL_WAIT_MS = 1_000L
    }
}
