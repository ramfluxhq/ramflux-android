// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
sealed interface CallsEvent {
    data object Back : CallsEvent
    data object SetupAccount : CallsEvent
}
