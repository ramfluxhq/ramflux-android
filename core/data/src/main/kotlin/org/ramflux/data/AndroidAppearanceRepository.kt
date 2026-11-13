// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import android.content.Context
import android.annotation.SuppressLint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidAppearanceRepository(context: Context) : AppearanceRepository {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val guard = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(AppearanceSnapshot())
    override val appearance = mutable.asStateFlow()
    private val initialization = scope.launch { guard.withLock {
        mutable.value = try {
            val stored = preferences.getString("appearance", null)
            AppearanceSnapshot(Appearance.entries.firstOrNull { it.name == stored } ?: Appearance.SYSTEM, loading = false)
        } catch (_: RuntimeException) {
            AppearanceSnapshot(loading = false, problem = ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    } }
    // commit runs on IO: unlike apply(), its result confirms whether storage succeeded.
    @SuppressLint("ApplySharedPref")
    override suspend fun setAppearance(value: Appearance): ClientResult<Unit> = withContext(Dispatchers.IO) {
        initialization.join()
        guard.withLock {
            val stored = try { preferences.edit().putString("appearance", value.name).commit() } catch (_: RuntimeException) { false }
            if (stored) { mutable.value = AppearanceSnapshot(value, loading = false); ClientResult.Ready(Unit) }
            else ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    }
}
