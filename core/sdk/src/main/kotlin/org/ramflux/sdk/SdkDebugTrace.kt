// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.sdk

/** Debug sink for an SDK failure line. Release never installs one. */
fun interface SdkDebugTrace {
    fun event(message: String)
}

/** Installs the process sink. A null sink restores the release behavior. */
fun installSdkDebugTrace(trace: SdkDebugTrace?) {
    ReplyCodec.debugTrace = trace
}

/** Forwards one diagnostic line to the installed sink; nothing happens without one (release). */
fun sdkDebugEvent(message: String) {
    ReplyCodec.debugTrace?.event(message)
}
