// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
sealed interface CardsAction {
    data class Visible(
        val value: Boolean
    ) : CardsAction
    data object Refresh : CardsAction
    data object Back : CardsAction
}
