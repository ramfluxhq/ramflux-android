// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.signals

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.api.Detection
import org.ramflux.push.api.IntentCandidate
import org.ramflux.push.api.StepWeight

/** The standard Android settings that decide whether a wake is delivered, the same on every brand. */
enum class StandardItem(val id: String) {
    NOTIFICATIONS("notifications"),
    MESSAGES_CHANNEL("messages_channel"),
    CALLS_CHANNEL("calls_channel"),
    FULL_SCREEN("full_screen"),
    BATTERY("battery"),
    BACKGROUND("background"),
    STANDBY("standby"),
    DATA_SAVER("data_saver"),
    HIBERNATION("hibernation"),
    BATTERY_SAVER("battery_saver")
}

/** One live reading. A null [weight] marks evidence that is shown as context and never as a fix. */
data class StandardReading(
    val item: StandardItem,
    val weight: StepWeight?,
    val detection: Detection,
    val candidates: List<IntentCandidate>
)

/** Reads the detectable standard settings (design 3.1). An item that this Android version or phone cannot report is left out. */
@Singleton
class AndroidDeliverySignals @Inject constructor(@ApplicationContext private val context: Context) {
    fun read(): List<StandardReading> = listOfNotNull(
        notifications(),
        channel(StandardItem.MESSAGES_CHANNEL, CHANNEL_MESSAGES) { it.importance != NotificationManager.IMPORTANCE_NONE },
        channel(StandardItem.CALLS_CHANNEL, CHANNEL_CALLS) {
            it.importance >= NotificationManager.IMPORTANCE_HIGH && it.sound != null
        },
        fullScreen(),
        battery(),
        background(),
        standby(),
        dataSaver(),
        hibernation(),
        batterySaver()
    )

    private fun notifications(): StandardReading? = guarded(StandardItem.NOTIFICATIONS) {
        val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        StandardReading(
            StandardItem.NOTIFICATIONS,
            StepWeight.REQUIRED,
            detection(enabled),
            listOf(
                IntentCandidate.Action(
                    Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    packageUri = false,
                    extras = mapOf(Settings.EXTRA_APP_PACKAGE to IntentCandidate.APP_PACKAGE)
                )
            )
        )
    }

    private fun channel(item: StandardItem, id: String, ok: (android.app.NotificationChannel) -> Boolean): StandardReading? = guarded(
        item
    ) {
        // A channel that does not exist yet is not a problem the person can fix.
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(id) ?: return@guarded null
        StandardReading(
            item,
            StepWeight.REQUIRED,
            detection(ok(channel)),
            listOf(
                IntentCandidate.Action(
                    Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
                    packageUri = false,
                    extras = mapOf(Settings.EXTRA_APP_PACKAGE to IntentCandidate.APP_PACKAGE, Settings.EXTRA_CHANNEL_ID to id)
                )
            )
        )
    }

    private fun fullScreen(): StandardReading? = guarded(StandardItem.FULL_SCREEN) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@guarded null
        val allowed = context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        StandardReading(
            StandardItem.FULL_SCREEN,
            StepWeight.REQUIRED,
            detection(allowed),
            listOf(IntentCandidate.Action(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri = true))
        )
    }

    /** Opens the system list of battery-optimised apps: the direct request is not used (design decision D3). */
    private fun battery(): StandardReading? = guarded(StandardItem.BATTERY) {
        val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        StandardReading(
            StandardItem.BATTERY,
            StepWeight.RECOMMENDED,
            detection(exempt),
            listOf(IntentCandidate.Action(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, packageUri = false))
        )
    }

    private fun background(): StandardReading? = guarded(StandardItem.BACKGROUND) {
        val restricted = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
        StandardReading(StandardItem.BACKGROUND, StepWeight.REQUIRED, detection(!restricted), listOf(appDetails()))
    }

    private fun standby(): StandardReading? = guarded(StandardItem.STANDBY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded null
        val bucket = context.getSystemService(UsageStatsManager::class.java).appStandbyBucket
        StandardReading(StandardItem.STANDBY, null, detection(bucket < UsageStatsManager.STANDBY_BUCKET_RESTRICTED), emptyList())
    }

    private fun dataSaver(): StandardReading? = guarded(StandardItem.DATA_SAVER) {
        val status = context.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus
        StandardReading(
            StandardItem.DATA_SAVER,
            StepWeight.RECOMMENDED,
            detection(status != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED),
            listOf(IntentCandidate.Action(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, packageUri = true))
        )
    }

    private fun hibernation(): StandardReading? = guarded(StandardItem.HIBERNATION) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@guarded null
        val exempt = context.packageManager.isAutoRevokeWhitelisted
        StandardReading(
            StandardItem.HIBERNATION,
            StepWeight.RECOMMENDED,
            detection(exempt),
            listOf(IntentCandidate.Action(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, packageUri = true))
        )
    }

    private fun batterySaver(): StandardReading? = guarded(StandardItem.BATTERY_SAVER) {
        val saving = context.getSystemService(PowerManager::class.java).isPowerSaveMode
        StandardReading(
            StandardItem.BATTERY_SAVER,
            null,
            detection(!saving),
            listOf(IntentCandidate.Action(Settings.ACTION_BATTERY_SAVER_SETTINGS, packageUri = false))
        )
    }

    private fun appDetails() = IntentCandidate.Action(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri = true)

    private fun detection(ok: Boolean) = if (ok) Detection.OK else Detection.PROBLEM

    /** A system service that refuses or is missing leaves the item out instead of failing the page. */
    private inline fun guarded(item: StandardItem, block: () -> StandardReading?): StandardReading? = try {
        block()
    } catch (refused: SecurityException) {
        traceUnavailable(item, refused)
    } catch (failed: RuntimeException) {
        traceUnavailable(item, failed)
    }

    /** Debug builds only: which reading could not be taken and why (the exception type), nothing else. */
    private fun traceUnavailable(item: StandardItem, cause: Exception): StandardReading? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d(TRACE_TAG, "push.signal.unavailable" + ' ' + item.id + ' ' + cause.javaClass.simpleName)
        }
        return null
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        private const val TRACE_TAG = "RamfluxPush"
        const val CHANNEL_CALLS = "calls"
    }
}
