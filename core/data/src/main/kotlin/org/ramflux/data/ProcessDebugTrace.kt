// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

import org.ramflux.sdk.installSdkDebugTrace

/** Forwards one SDK failure line. Callers install this only on a debug build. */
fun installProcessDebugTrace(event: (String) -> Unit) {
    installSdkDebugTrace { event(it) }
}

/** One client diagnostic line for the debug sink (codes and names only, never content); a no-op in release. */
fun clientDebugEvent(line: String) {
    org.ramflux.sdk.sdkDebugEvent(line)
}
