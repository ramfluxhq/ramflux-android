// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding

/** Sink for confirm outcome tokens. A release binding discards every event. */
fun interface ConfirmTrace {
    fun event(message: String)
}
