// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import kotlinx.coroutines.flow.StateFlow

enum class Appearance { SYSTEM, LIGHT, DARK }
data class AppearanceSnapshot(val value: Appearance = Appearance.SYSTEM, val loading: Boolean = true, val problem: ClientProblem? = null)

interface AppearanceRepository {
    val appearance: StateFlow<AppearanceSnapshot>
    suspend fun setAppearance(value: Appearance): ClientResult<Unit>
}
