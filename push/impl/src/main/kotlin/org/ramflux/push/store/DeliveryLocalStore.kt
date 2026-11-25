// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.store

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.settings.SettingsTier

/**
 * What the delivery page remembers on this phone, in app-private preferences that backup excludes. Nothing here is
 * sent anywhere, and it holds no wake ids, route tags, account ids or component names.
 */
@Singleton
class DeliveryLocalStore @Inject constructor(@ApplicationContext context: Context) {
    private val preferences: SharedPreferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** The person marked an undetectable step as done; remembered with the OS stamp it was marked under. */
    fun confirm(stepId: String, nowMs: Long) {
        preferences.edit { putString(confirmedKey(stepId), nowMs.toString() + SEPARATOR + osStamp()) }
    }

    fun unconfirm(stepId: String) {
        preferences.edit { remove(confirmedKey(stepId)) }
    }

    /** True while the confirmation was made under the current OS stamp; a changed stamp drops it, so the step is offered again once. */
    fun isConfirmed(stepId: String): Boolean {
        val value = preferences.getString(confirmedKey(stepId), null) ?: return false
        if (value.substringAfter(SEPARATOR, "") == osStamp()) return true
        unconfirm(stepId)
        return false
    }

    /** Counts a settings launch per step and tier, never the component that was opened. */
    fun countOpen(stepId: String, tier: SettingsTier) {
        val key = openKey(stepId, tier)
        preferences.edit { putInt(key, preferences.getInt(key, 0) + 1) }
    }

    fun openCount(stepId: String, tier: SettingsTier): Int = preferences.getInt(openKey(stepId, tier), 0)

    fun lastWakeMs(): Long = preferences.getLong("wake.last", 0L)

    fun markSheetShown(nowMs: Long) {
        preferences.edit { putLong("sheet.g1.shown", nowMs) }
    }

    fun sheetShown(): Boolean = preferences.getLong("sheet.g1.shown", 0L) > 0L

    fun bannerLastMs(problem: String): Long = preferences.getLong("banner.$problem.last", 0L)

    fun markBanner(problem: String, nowMs: Long) {
        preferences.edit { putLong("banner.$problem.last", nowMs) }
    }

    /** The start identity of the last force stop this install has already announced. */
    fun handledStart(): Long = preferences.getLong("exit.handled", 0L)

    fun markStartHandled(identity: Long) {
        preferences.edit { putLong("exit.handled", identity) }
    }

    private fun confirmedKey(stepId: String) = "step.$stepId.confirmed"

    private fun osStamp() = Build.VERSION.SDK_INT.toString() + SEPARATOR + Build.VERSION.INCREMENTAL

    private fun openKey(stepId: String, tier: SettingsTier) = "settings.open." + stepId + "." + tier.name.lowercase()

    private companion object {
        const val NAME = "push-delivery"
        const val SEPARATOR = '|'
    }
}
