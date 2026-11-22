// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.ramflux.data.AppLanguage
import org.ramflux.data.ClientFailureKind
import org.ramflux.data.ClientOutcome
import org.ramflux.data.ClientProblem
import org.ramflux.data.ClientResult
import org.ramflux.data.LanguageRepository
import org.ramflux.data.LanguageSnapshot

/**
 * The app language on API 33+: the platform per-app language is the single source of truth, so the
 * in-app picker and the system's per-app language screen always agree, and non-Compose surfaces
 * follow the same choice. The picker writes it; a change made in system settings is read back on the
 * configuration change it causes. A choice stored by the device-local store before this release is
 * moved to the platform once. Peer data and account permissions are never touched.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class PlatformLanguageRepository(context: Context, private val stored: LanguageRepository) : LanguageRepository {
    private val manager = requireNotNull(context.getSystemService(LocaleManager::class.java))
    private val mutable = MutableStateFlow(LanguageSnapshot(loading = true))
    override val language: StateFlow<LanguageSnapshot> = mutable.asStateFlow()

    init {
        context.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    mutable.value = read()
                }

                override fun onLowMemory() = Unit
            }
        )
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val previous = stored.language.first { !it.loading }.value
            if (previous != AppLanguage.SYSTEM && manager.applicationLocales.isEmpty) {
                manager.applicationLocales = LocaleList.forLanguageTags(previous.tag)
                stored.setLanguage(AppLanguage.SYSTEM)
            }
            mutable.value = read()
        }
    }

    override suspend fun setLanguage(value: AppLanguage): ClientResult<Unit> {
        manager.applicationLocales = LocaleList.forLanguageTags(value.tag)
        mutable.value = read()
        return ClientResult.Ready(Unit)
    }

    private fun read(): LanguageSnapshot {
        val tag = manager.applicationLocales.toLanguageTags()
        val language = AppLanguage.entries.find { it.tag == tag }
        return if (language != null) {
            LanguageSnapshot(language, loading = false)
        } else {
            // A platform choice the picker cannot name (for example a resource locale added before the
            // picker learns it): resources still follow the platform; the picker shows a notice.
            LanguageSnapshot(
                AppLanguage.SYSTEM,
                loading = false,
                problem = ClientProblem(ClientFailureKind.UNAVAILABLE, 4, ClientOutcome.NONE)
            )
        }
    }
}

/** API 33+ use the platform per-app language; API 28 to 32 keep the device-local store. */
internal fun appLanguageRepository(context: Context, stored: LanguageRepository): LanguageRepository =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PlatformLanguageRepository(context, stored) else stored
