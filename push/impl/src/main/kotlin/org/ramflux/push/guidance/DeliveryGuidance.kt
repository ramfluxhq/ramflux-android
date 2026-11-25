// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.guidance

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.ramflux.push.api.Detection
import org.ramflux.push.api.DeviceGuidance
import org.ramflux.push.api.IntentCandidate
import org.ramflux.push.api.StepWeight
import org.ramflux.push.impl.R
import org.ramflux.push.settings.SafeSettingsLauncher
import org.ramflux.push.signals.AndroidDeliverySignals
import org.ramflux.push.signals.StandardItem
import org.ramflux.push.signals.StandardReading
import org.ramflux.push.store.DeliveryLocalStore

enum class EntryGroup { PHONE, CALLS, BRAND }

/** What the line says about one setting (design 3.5): never a check mark for something Ramflux cannot read. */
enum class EntryLabel { ON, OFF_LATE, NOT_CHECKED, MARKED_DONE, EVIDENCE_PROBLEM }

data class DeliveryEntry(
    val id: String,
    val group: EntryGroup,
    @StringRes val title: Int,
    @StringRes val body: Int,
    val label: EntryLabel,
    val required: Boolean,
    val canOpen: Boolean,
    val canConfirm: Boolean,
    val vendorScreen: Boolean
)

data class DeliveryPage(
    @StringRes val brandLabel: Int,
    @StringRes val skinHint: Int?,
    val osRelease: String,
    val entries: List<DeliveryEntry>,
    val candidates: Map<String, List<IntentCandidate>>
)

/** Builds the page: the standard readings and this brand's vendor steps, with what the person already confirmed. */
@Singleton
class DeliveryGuidance @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signals: AndroidDeliverySignals,
    private val store: DeliveryLocalStore,
    private val device: DeviceGuidance,
    private val launcher: SafeSettingsLauncher
) {
    fun read(): DeliveryPage {
        val identity = device.identity()
        val compat = device.compat()
        val candidates = mutableMapOf<String, List<IntentCandidate>>()
        val entries = mutableListOf<DeliveryEntry>()
        for (reading in signals.read()) {
            val id = reading.item.id
            candidates[id] = reading.candidates
            entries += standardEntry(reading)
        }
        for (step in compat.steps(identity)) {
            // A step that can only point at vendor screens this phone does not have is not offered.
            if (!launcher.resolvable(context, step.candidates)) continue
            candidates[step.id] = step.candidates
            val label = when (step.detection) {
                Detection.OK -> EntryLabel.ON
                Detection.PROBLEM -> EntryLabel.OFF_LATE
                Detection.UNDETECTABLE -> if (store.isConfirmed(step.id)) EntryLabel.MARKED_DONE else EntryLabel.NOT_CHECKED
            }
            entries += DeliveryEntry(
                id = step.id,
                group = EntryGroup.BRAND,
                title = step.title,
                body = step.body,
                label = label,
                required = step.weight == StepWeight.REQUIRED,
                canOpen = step.candidates.isNotEmpty(),
                canConfirm = step.detection == Detection.UNDETECTABLE,
                vendorScreen = launcher.hasVendorScreen(step.candidates)
            )
        }
        return DeliveryPage(compat.brandLabel(identity), compat.skinHint(identity), Build.VERSION.RELEASE.orEmpty(), entries, candidates)
    }

    private fun standardEntry(reading: StandardReading): DeliveryEntry {
        val (title, body) = standardText(reading.item)
        val problem = reading.detection == Detection.PROBLEM
        return DeliveryEntry(
            id = reading.item.id,
            group = if (reading.item in CALL_ITEMS) EntryGroup.CALLS else EntryGroup.PHONE,
            title = title,
            body = body,
            label = when {
                !problem -> EntryLabel.ON
                reading.weight == null -> EntryLabel.EVIDENCE_PROBLEM
                else -> EntryLabel.OFF_LATE
            },
            required = reading.weight == StepWeight.REQUIRED,
            canOpen = problem && reading.candidates.isNotEmpty(),
            canConfirm = false,
            vendorScreen = false
        )
    }

    private fun standardText(item: StandardItem): Pair<Int, Int> = when (item) {
        StandardItem.NOTIFICATIONS -> R.string.delivery_notifications_title to R.string.delivery_notifications_body
        StandardItem.MESSAGES_CHANNEL -> R.string.delivery_messages_title to R.string.delivery_messages_body
        StandardItem.CALLS_CHANNEL -> R.string.delivery_calls_title to R.string.delivery_calls_body
        StandardItem.FULL_SCREEN -> R.string.delivery_full_screen_title to R.string.delivery_full_screen_body
        StandardItem.BATTERY -> R.string.delivery_battery_title to R.string.delivery_battery_body
        StandardItem.BACKGROUND -> R.string.delivery_background_title to R.string.delivery_background_body
        StandardItem.STANDBY -> R.string.delivery_standby_title to R.string.delivery_standby_body
        StandardItem.DATA_SAVER -> R.string.delivery_data_saver_title to R.string.delivery_data_saver_body
        StandardItem.HIBERNATION -> R.string.delivery_hibernation_title to R.string.delivery_hibernation_body
        StandardItem.BATTERY_SAVER -> R.string.delivery_battery_saver_title to R.string.delivery_battery_saver_body
    }
}

private val CALL_ITEMS = setOf(StandardItem.CALLS_CHANNEL, StandardItem.FULL_SCREEN)
