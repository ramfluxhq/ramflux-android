// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
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
        background = scope.launch { session.background() }
    }
}
