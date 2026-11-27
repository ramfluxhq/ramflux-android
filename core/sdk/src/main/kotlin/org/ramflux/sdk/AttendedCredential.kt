// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import java.util.concurrent.ConcurrentHashMap

/** How long the host may take to answer one attended request. The native callback waits as long as well. */
internal const val ATTENDED_ANSWER_MS = 60_000L

/** Thrown when an attended request is started while another one of the same client is still running. */
class AttendedCodeBusyException : IllegalStateException("an attended account-code request is already running")

/**
 * Receives the core's attended requests from the native callback (`credential_jni.cpp`) and routes each one to the
 * open [CoreClient.withAttendedCode] of its handle. It runs on a native thread: it only posts, never blocks and never
 * calls the SDK.
 */
object AttendedCredentialBridge {
    private val listeners = ConcurrentHashMap<Long, (Int) -> Unit>()

    internal fun listen(core: Long, onRequest: (Int) -> Unit) {
        listeners[core] = onRequest
    }

    internal fun unlisten(core: Long) {
        listeners.remove(core)
    }

    @JvmStatic
    fun onRequest(core: Long, action: Int) {
        listeners[core]?.invoke(action)
    }
}
