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
            AppearanceSnapshot(Appearance.entries.firstOrNull { it.name == stored } ?: Appearance.SYSTEM, loading = false,
                themeId = preferences.getString("theme_id", null))
        } catch (_: RuntimeException) {
            AppearanceSnapshot(loading = false, problem = ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    } }
    // commit runs on IO: unlike apply(), its result confirms whether storage succeeded.
    @SuppressLint("ApplySharedPref")
    override suspend fun setAppearance(value: Appearance): ClientResult<Unit> = withContext(Dispatchers.IO) {
        initialization.join()
        guard.withLock {
            val stored = try { preferences.edit().putString("appearance", value.name).remove("theme_id").commit() } catch (_: RuntimeException) { false }
            if (stored) { mutable.value = AppearanceSnapshot(value, loading = false); ClientResult.Ready(Unit) }
            else ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    }

    @SuppressLint("ApplySharedPref")
    override suspend fun setNamedTheme(id: String): ClientResult<Unit> = withContext(Dispatchers.IO) {
        if (!Regex("[a-z][a-z0-9_]{0,63}").matches(id)) {
            return@withContext ClientResult.Failed(ClientProblem(ClientFailureKind.FAILED, 1, ClientOutcome.NONE))
        }
        initialization.join()
        guard.withLock {
            val legacy = when (id) { "light" -> Appearance.LIGHT; "dark" -> Appearance.DARK; else -> Appearance.SYSTEM }
            val stored = try { preferences.edit().putString("appearance", legacy.name).putString("theme_id", id).commit() }
                catch (_: RuntimeException) { false }
            if (stored) { mutable.value = AppearanceSnapshot(legacy, loading = false, themeId = id); ClientResult.Ready(Unit) }
            else ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    }
}
