// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
sealed interface CardsEvent {
    data object Back : CardsEvent
}
