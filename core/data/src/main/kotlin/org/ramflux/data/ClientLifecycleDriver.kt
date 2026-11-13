// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import org.ramflux.sdk.*

/** Data-internal native boundary, so lifecycle ordering can be verified without a native process. */
internal interface ClientLifecycleDriver {
    suspend fun open(): ClientResult<Unit>
    suspend fun close(): ClientResult<Unit>
}
internal class AndroidClientLifecycleDriver(private val context: Context, private val descriptor: String) : ClientLifecycleDriver {
    private var host: AndroidCoreHost? = null
    override suspend fun open(): ClientResult<Unit> {
        if (host == null) when (val created = AndroidCoreHost.create(context, descriptor)) {
            is CoreResult.Failure -> return ClientResult.Failed(created.error.appProblem())
            is CoreResult.Success -> host = created.value
        }
        return when (val opened = host?.foreground()) {
            is CoreResult.Success -> ClientResult.Ready(Unit)
            is CoreResult.Failure -> ClientResult.Failed(opened.error.appProblem())
            null -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, -1, ClientOutcome.NONE))
        }
    }
    override suspend fun close(): ClientResult<Unit> {
        val current = host ?: return ClientResult.Ready(Unit)
        return when (val closed = current.background()) {
            is CoreResult.Failure -> ClientResult.Failed(closed.error.appProblem())
            is CoreResult.Success -> {
                // A repeated background on a retained failed close is a no-op in this SDK pin.
                val state = current.state.value
                if (state is HostState.Failed && state.shutdownPending) ClientResult.Failed(state.error.appProblem())
                else ClientResult.Ready(Unit)
            }
        }
    }
}
