// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import org.ramflux.sdk.*

/** Data-internal native boundary, so lifecycle ordering can be verified without a native process. */
internal interface ClientLifecycleDriver {
    suspend fun open(): ClientResult<Unit>
    /** Suspends the open client for the background (files and network released, accounts kept unlocked). */
    suspend fun suspend(): ClientResult<Unit>

    /** The app lock of the open account. The default is an unreadable state, so nothing is shown until a driver says otherwise. */
    suspend fun appLockRead(): AppLockReading = UNKNOWN_APP_LOCK

    /** Sends `account.app_lock.lifecycle` for the open account and returns the state its reply carries (null when none). */
    suspend fun appLockLifecycle(state: String): AppLockState? = null

    /** Registers the one listener called for every SDK refusal that names the app lock. */
    fun onAppLockRefused(listener: () -> Unit) = Unit
}
internal class AndroidClientLifecycleDriver(private val context: Context, private val descriptor: String, private val openConfig: CoreOpenConfig = CoreOpenConfig()) : ClientLifecycleDriver {
    @Volatile private var host: AndroidCoreHost? = null
    @Volatile private var refused: (() -> Unit)? = null
    private val sdk: RepositoryTransport = SdkRepositoryTransport { (host?.state?.value as? HostState.Running)?.client }
    val transport: RepositoryTransport = AppLockRefusalTransport(sdk) { refused?.invoke() }
    private val reader = AppLockReader(transport)
    override suspend fun open(): ClientResult<Unit> {
        if (host == null) when (val created = AndroidCoreHost.create(context, descriptor, openConfig)) {
            is CoreResult.Failure -> return ClientResult.Failed(created.error.appProblem())
            is CoreResult.Success -> host = created.value
        }
        return when (val opened = host?.foreground()) {
            is CoreResult.Success -> ClientResult.Ready(Unit)
            is CoreResult.Failure -> ClientResult.Failed(opened.error.appProblem())
            null -> ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, -1, ClientOutcome.NONE))
        }
    }
    override suspend fun suspend(): ClientResult<Unit> {
        val current = host ?: return ClientResult.Ready(Unit)
        return when (val suspended = current.background()) {
            is CoreResult.Failure -> ClientResult.Failed(suspended.error.appProblem())
            is CoreResult.Success -> ClientResult.Ready(Unit)
        }
    }
    override suspend fun appLockRead(): AppLockReading = reader.read()
    override suspend fun appLockLifecycle(state: String): AppLockState? = reader.lifecycle(state)
    override fun onAppLockRefused(listener: () -> Unit) {
        refused = listener
    }
}
