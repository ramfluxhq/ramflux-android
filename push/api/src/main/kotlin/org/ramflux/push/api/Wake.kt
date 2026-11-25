// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.api

enum class WakeClass { MESSAGE, CALL, CLEAR, TEST, UNKNOWN }

/** A content-free wake. The route tag and wake id are identifiers, so [toString] hides them. */
class WakeSignal(
    val wakeClass: WakeClass,
    val routeTag: String,
    val wakeId: String,
    val highPriority: Boolean,
    val downgraded: Boolean
) {
    override fun toString(): String = "WakeSignal(<redacted>)"
}

interface WakeSink {
    /** Called on the provider's thread; the work must finish by [deadlineMs]. */
    fun onWake(signal: WakeSignal, deadlineMs: Long)
}
