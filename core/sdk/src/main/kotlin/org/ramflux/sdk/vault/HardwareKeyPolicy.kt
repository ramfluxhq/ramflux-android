// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk.vault

internal object HardwareKeyPolicy {
    /** Only the helper's explicitly verified TEE (1) and StrongBox (2) levels are accepted. */
    fun accepts(level: Int): Boolean = level == 1 || level == 2
}
