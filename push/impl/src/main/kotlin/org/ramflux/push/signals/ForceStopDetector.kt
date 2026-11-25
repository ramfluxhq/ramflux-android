// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.signals

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Finds the last time the person stopped the app from the system (design 3.1, "Force stop"); Android 11 and later. */
@Singleton
class ForceStopDetector @Inject constructor(@ApplicationContext private val context: Context) {
    /** Timestamp of the newest recorded exit requested by the person, or null when none is recorded. */
    fun latestMs(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
                .filter { it.reason == ApplicationExitInfo.REASON_USER_REQUESTED }
                .maxOfOrNull { it.timestamp }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val MAX_RECORDS = 16
    }
}
