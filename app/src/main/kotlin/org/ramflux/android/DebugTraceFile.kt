// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Debug and realrun builds only: the debug trace (codes and names, never content) is also appended to a bounded file in
 * the app's no-backup storage. Some vendor builds keep none of an app's own log lines from its main process, so the
 * trace cannot rely on logcat; a tester reads the file with `run-as` (scripts/realrun.sh copies it to app-trace.txt).
 * Writes run on one background thread; the file starts over when it passes [MAX_BYTES].
 */
internal class DebugTraceFile(private val file: File) {
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "debug-trace").apply { isDaemon = true } }

    fun write(line: String) {
        val stamp = System.currentTimeMillis()
        writer.execute {
            try {
                if (file.length() > MAX_BYTES) file.writeText("")
                file.appendText(stamp.toString() + SEPARATOR + line + NEWLINE)
            } catch (_: IOException) {
                // A lost trace line never affects the app.
            }
        }
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024
        const val SEPARATOR = ' '
        const val NEWLINE = '\n'
    }
}
