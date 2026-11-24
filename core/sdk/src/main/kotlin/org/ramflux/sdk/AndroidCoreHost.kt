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
    /** Backgrounded: files closed and networking stopped, unlocked accounts kept in memory (C-ABI-SUSPEND-01). */
    class Suspended(val client: CoreClient) : HostState
    data class Failed(val error: CoreError, val shutdownPending: Boolean = false) : HostState
}

/**
 * Application lifecycle owner: one supervisor, no service or socket. In the background the client is suspended (no
 * file or network held, accounts stay unlocked in memory) and resumed on return without the account code; a new
 * process opens with the device-bound custody key, so it asks for no code either.
 */
class AndroidCoreHost internal constructor(private val native: NativeCore, private val dataRoot: String, private val expectedDescriptorFingerprint: String) {
    private val transitions = Mutex()
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
            if (current is HostState.Suspended) {
                return@withLock when (val resumed = current.client.resume()) {
                    is CoreResult.Success -> {
                        mutableState.value = HostState.Running(current.client)
                        CoreResult.Success(current.client)
                    }
                    // A data-root conflict leaves the client suspended; the next foreground tries again.
                    is CoreResult.Failure -> resumed
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

    /** Background: suspending is a completed transition (files closed when it returns), never a fire-and-forget callback. */
    suspend fun background(): CoreResult<kotlinx.serialization.json.JsonObject> = withContext(NonCancellable) {
        transitions.withLock {
            val current = mutableState.value
            if (current !is HostState.Running) return@withLock CoreResult.Success(kotlinx.serialization.json.JsonObject(emptyMap()))
            // A failed suspend can leave the client partly suspended (its calls refused as suspended, the event pump
            // paused). Unless it closed, it is treated as suspended so the next foreground resumes it; resuming a
            // client that is not suspended does nothing.
            current.client.suspend().also {
                if (it is CoreResult.Success || (it as CoreResult.Failure).error !is CoreError.Closed) {
                    mutableState.value = HostState.Suspended(current.client)
                }
            }
        }
    }
}
