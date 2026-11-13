// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import kotlinx.coroutines.flow.StateFlow

enum class AppLanguage(val tag: String) { SYSTEM(""), ENGLISH("en"), SIMPLIFIED_CHINESE("zh-CN") }
data class LanguageSnapshot(val value: AppLanguage = AppLanguage.SYSTEM, val loading: Boolean = true, val problem: ClientProblem? = null)
/** Device-local host locale. Peer text and account permissions are never changed. */
interface LanguageRepository {
    val language: StateFlow<LanguageSnapshot>
    suspend fun setLanguage(value: AppLanguage): ClientResult<Unit>
}
