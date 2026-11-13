// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import android.content.Context
import android.annotation.SuppressLint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidLanguageRepository(context: Context) : LanguageRepository {
    private val preferences = context.getSharedPreferences("language", Context.MODE_PRIVATE)
    private val guard = Mutex()
    private val mutable = MutableStateFlow(LanguageSnapshot())
    override val language = mutable.asStateFlow()
    private val initialization = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        guard.withLock {
            mutable.value = try {
                val stored = preferences.getString("language_tag", "")
                val language = AppLanguage.entries.find { it.tag == stored }
                if (language == null) LanguageSnapshot(loading = false, problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE))
                else LanguageSnapshot(language, loading = false)
            } catch (_: RuntimeException) {
                LanguageSnapshot(loading = false, problem = ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
            }
        }
    }
    // Only a confirmed IO commit becomes the active language observed by the host composition.
    @SuppressLint("ApplySharedPref")
    override suspend fun setLanguage(value: AppLanguage): ClientResult<Unit> = withContext(Dispatchers.IO) {
        initialization.join()
        guard.withLock {
            val stored = try { preferences.edit().putString("language_tag", value.tag).commit() } catch (_: RuntimeException) { false }
            if (stored) {
                mutable.value = LanguageSnapshot(value, loading = false)
                ClientResult.Ready(Unit)
            } else ClientResult.Failed(ClientProblem(ClientFailureKind.STORAGE, 8, ClientOutcome.NONE))
        }
    }
}
