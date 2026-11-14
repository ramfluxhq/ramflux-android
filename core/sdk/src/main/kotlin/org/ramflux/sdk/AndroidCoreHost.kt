// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ramflux.sdk.vault.HostCustodyBridge

sealed interface HostState {
    data object Stopped : HostState
    class Running(val client: CoreClient) : HostState
    data class Failed(val error: CoreError, val shutdownPending: Boolean = false) : HostState
}

/** Application lifecycle owner: one supervisor, no service or socket, no store held in background. */
class AndroidCoreHost internal constructor(private val native: NativeCore, private val dataRoot: String, private val expectedDescriptorFingerprint: String) {
    private val transitions = Mutex()
    private var retiring: CoreClient? = null
    private val mutableState = MutableStateFlow<HostState>(HostState.Stopped)
    val state: StateFlow<HostState> = mutableState.asStateFlow()

    companion object {
        suspend fun create(context: android.content.Context, expectedDescriptorFingerprint: String): CoreResult<AndroidCoreHost> =
            withContext(kotlinx.coroutines.Dispatchers.IO) { when (val root = AndroidStorage.noBackupRoot(context)) {
                is CoreResult.Failure -> root
                is CoreResult.Success -> try {
                    val native = JniCore.load()
                    HostCustodyBridge.install(context.applicationContext)
                    CoreResult.Success(AndroidCoreHost(native, root.value.absolutePath, expectedDescriptorFingerprint))
                } catch (_: LinkageError) { CoreResult.Failure(CoreError.UnsupportedVersion(Effect.NONE)) }
            } }
    }

    suspend fun foreground(): CoreResult<CoreClient> = withContext(NonCancellable) {
        transitions.withLock {
            val current = mutableState.value
            if (current is HostState.Running) return@withLock CoreResult.Success(current.client)
            retiring?.let { previous ->
                when (val stopped = previous.close()) {
                    is CoreResult.Success -> retiring = null
                    is CoreResult.Failure -> {
                        if (stopped.error.code in setOf(10, 24)) return@withLock stopped
                        val released = previous.releaseFailedClose()
                        if (released is CoreResult.Failure) return@withLock released
                        retiring = null
                    }
                }
            }
            when (val result = CoreClient.open(native, dataRoot, expectedDescriptorFingerprint)) {
                is CoreResult.Success -> {
                    mutableState.value = HostState.Running(result.value)
                    result
                }
                is CoreResult.Failure -> {
                    mutableState.value = HostState.Failed(result.error,
                        shutdownPending = current is HostState.Failed && current.shutdownPending)
                    result
                }
            }
        }
    }

    /** Releasing the writer is a completed transition, never a fire-and-forget callback. */
    suspend fun background(): CoreResult<kotlinx.serialization.json.JsonObject> = withContext(NonCancellable) {
        transitions.withLock {
            val current = mutableState.value
            if (current !is HostState.Running) return@withLock CoreResult.Success(kotlinx.serialization.json.JsonObject(emptyMap()))
            val result = current.client.close()
            if (result is CoreResult.Failure) retiring = current.client
            mutableState.value = when (result) {
                is CoreResult.Success -> HostState.Stopped
                is CoreResult.Failure -> HostState.Failed(result.error, shutdownPending = true)
            }
            result
        }
    }
}
