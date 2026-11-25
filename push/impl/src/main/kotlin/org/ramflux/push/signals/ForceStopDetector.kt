// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.signals

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.ApplicationStartInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the current process was started after the person force-stopped the app from the system (design 3.1,
 * "Force stop"). Only Android 15 and later say so (`ApplicationStartInfo.wasForceStopped`): earlier exit records
 * cannot tell a force stop from a swipe out of the recents list, which does not stop FCM, so nothing is reported there.
 * A process that another app killed in the background is indistinguishable from a force stop here and is reported too.
 */
@Singleton
class ForceStopDetector @Inject constructor(@ApplicationContext private val context: Context) {
    /** An identity of the current start when it followed a force stop, else null. It differs for every such start. */
    fun currentStartAfterForceStop(): Long? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        return try {
            val manager = context.getSystemService(ActivityManager::class.java)
            val start = manager.getHistoricalProcessStartReasons(1).firstOrNull()
            // On its own the start flag also follows a plain kill of the process; the exit before it must be a request from the person.
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            if (start == null || !start.wasForceStopped() || exit?.reason != ApplicationExitInfo.REASON_USER_REQUESTED) {
                null
            } else {
                // The launch time of this start (monotonic), with the boot count so that it differs after a reboot.
                val launch = start.startupTimestamps[ApplicationStartInfo.START_TIMESTAMP_LAUNCH] ?: 0L
                val boots = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
                (boots.toLong() shl BOOT_SHIFT) or (launch and LAUNCH_MASK) or 1L
            }
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private companion object {
        const val BOOT_SHIFT = 44
        const val LAUNCH_MASK = (1L shl BOOT_SHIFT) - 1
    }
}
