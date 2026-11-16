// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell

/** Drop restored account screens while the shell is locked. Unlock then opens the shell home. */
fun <T> routesWhileLocked(stack: List<T>, shellReady: Boolean, isAccountRoute: (T) -> Boolean): List<T> =
    if (shellReady) stack else stack.filterNot(isAccountRoute)
