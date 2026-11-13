// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import androidx.compose.runtime.Immutable
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.cards.impl.R
@Immutable data class CardsState(
    val checking: Boolean = false,
    val canCheck: Boolean = false,
    val closed: Boolean = false,
    val unavailable: UiText? = uiText(R.string.cards_missing),
    val problem: UiText? = null
)
