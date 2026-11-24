// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.ramflux.data.clientDebugEvent

/**
 * Debug and realrun builds only: notes in the debug trace when the main thread stops answering for [LIMIT_MS], with the
 * top frames of its stack (class and method names only), so a frozen screen in a real run can be placed.
 */
internal object MainThreadWatch {
    private const val LIMIT_MS = 2_000L
    private const val PERIOD_MS = 500L
    private const val FRAMES = 12

    fun start() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var answered = SystemClock.uptimeMillis()
            var reported = false
            while (true) {
                main.post { answered = SystemClock.uptimeMillis() }
                Thread.sleep(PERIOD_MS)
                val silent = SystemClock.uptimeMillis() - answered
                if (silent > LIMIT_MS && !reported) {
                    reported = true
                    val frames = Looper.getMainLooper().thread.stackTrace.take(FRAMES)
                        .joinToString(".") { it.className.substringAfterLast('.') + ":" + it.methodName }
                    clientDebugEvent("main.blocked:" + silent + ".stack:" + frames)
                } else if (silent <= LIMIT_MS && reported) {
                    reported = false
                    clientDebugEvent("main.answered")
                }
            }
        }, "main-watch").apply { isDaemon = true }.start()
    }
}
