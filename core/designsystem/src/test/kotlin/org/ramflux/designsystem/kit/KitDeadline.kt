// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import android.os.Handler
import android.os.Looper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Bounds a Robolectric test without leaving the main thread. */
class KitDeadline(private val seconds: Long) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val watchdog = Thread({
                    try {
                        Thread.sleep(seconds * 1000)
                        Handler(Looper.getMainLooper()).post {
                            throw AssertionError("deadline ${seconds}s")
                        }
                    } catch (_: InterruptedException) {
                        // The test finished inside the deadline.
                    }
                }, "kit-deadline")
                watchdog.isDaemon = true
                watchdog.start()
                try {
                    base.evaluate()
                } finally {
                    watchdog.interrupt()
                }
            }
        }
    }
}
