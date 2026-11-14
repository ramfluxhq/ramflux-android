// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
sealed interface CallsAction {
    data class Visible(val value: Boolean) : CallsAction
    data object Refresh : CallsAction
    data object Back : CallsAction
    data object SetupAccount : CallsAction
    data class Select(val id: String) : CallsAction
    data class End(val id: String, val sequence: ULong, val decline: Boolean) : CallsAction
    data object Audio : CallsAction
    data object ToggleFacts : CallsAction
}
