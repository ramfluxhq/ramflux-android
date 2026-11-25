// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.triggers

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ramflux.push.api.ProviderState
import org.ramflux.push.api.PushProvider
import org.ramflux.push.api.UnavailableReason
import org.ramflux.push.guidance.DeliveryGuidance
import org.ramflux.push.guidance.EntryGroup
import org.ramflux.push.guidance.EntryLabel
import org.ramflux.push.signals.ForceStopDetector
import org.ramflux.push.signals.StandardItem
import org.ramflux.push.store.DeliveryLocalStore

/** A problem the conversation-list banner can raise, in the order it takes priority (design 5.1). */
enum class DeliveryProblem(val id: String) {
    MESSAGES_CHANNEL("messages_channel"),
    CALLS_CHANNEL("calls_channel"),
    FULL_SCREEN("full_screen"),
    BACKGROUND("background"),
    STANDBY("standby"),
    PLAY_SERVICES("play_services"),
    FORCE_STOP("force_stop")
}

/** The guidance triggers G1 (sheet), G2 (hard problem banner) and G4 (force stop). Nothing here opens a dialog on launch. */
@Singleton
class DeliveryTriggers @Inject constructor(
    @ApplicationContext private val context: Context,
    private val guidance: DeliveryGuidance,
    private val store: DeliveryLocalStore,
    private val provider: PushProvider,
    private val forceStops: ForceStopDetector
) {
    private val sheetRequested = MutableStateFlow(false)

    /** True after the person left the permission screen and the sheet has not been decided yet. */
    val sheetRequest: StateFlow<Boolean> = sheetRequested.asStateFlow()

    /** The permission flow returned; the sheet may show once, if the conditions of [sheetSteps] hold. */
    fun onPermissionFlowFinished() {
        if (!store.sheetShown()) sheetRequested.value = true
    }

    /**
     * G1: the titles of at most three steps to show, or null. Needs notifications allowed and either a REQUIRED
     * vendor step or a REQUIRED standard setting in trouble. Marks the sheet shown, so it appears once per install
     * whatever the person answers.
     */
    fun sheetSteps(): List<Int>? {
        sheetRequested.value = false
        if (store.sheetShown() || !notificationsEnabled()) return null
        val entries = guidance.read().entries
        val problems = entries.filter { it.required && it.group != EntryGroup.BRAND && it.label == EntryLabel.OFF_LATE }
        val vendor = entries.filter { it.required && it.group == EntryGroup.BRAND && it.label != EntryLabel.MARKED_DONE }
        val titles = (problems + vendor).map { it.title }.take(MAX_SHEET_STEPS)
        if (titles.isEmpty()) return null
        store.markSheetShown(System.currentTimeMillis())
        return titles
    }

    /** The hard problems present right now, most important first. A banner is not raised while notifications are off. */
    fun problems(): List<DeliveryProblem> {
        // The permission banner already covers notifications that are off; one push banner at a time.
        if (!notificationsEnabled()) return emptyList()
        val entries = guidance.read().entries.associateBy { it.id }
        fun off(item: StandardItem) = entries[item.id]?.label.let { it == EntryLabel.OFF_LATE || it == EntryLabel.EVIDENCE_PROBLEM }
        return buildList {
            if (off(StandardItem.MESSAGES_CHANNEL)) add(DeliveryProblem.MESSAGES_CHANNEL)
            if (off(StandardItem.CALLS_CHANNEL)) add(DeliveryProblem.CALLS_CHANNEL)
            if (off(StandardItem.FULL_SCREEN)) add(DeliveryProblem.FULL_SCREEN)
            if (off(StandardItem.BACKGROUND)) add(DeliveryProblem.BACKGROUND)
            if (off(StandardItem.STANDBY)) add(DeliveryProblem.STANDBY)
            if (playServicesProblem()) add(DeliveryProblem.PLAY_SERVICES)
            if (forceStopPending()) add(DeliveryProblem.FORCE_STOP)
        }
    }

    /** G2 and G4: the problem to raise now, or null. Each problem is raised once per seven days; a force stop once per stop. */
    fun nextBanner(nowMs: Long): DeliveryProblem? = problems().firstOrNull { problem ->
        problem == DeliveryProblem.FORCE_STOP || nowMs - store.bannerLastMs(problem.id) >= BANNER_INTERVAL_MS
    }

    /** Records that the banner for [problem] was shown. */
    fun markBannerShown(problem: DeliveryProblem, nowMs: Long) {
        store.markBanner(problem.id, nowMs)
        if (problem == DeliveryProblem.FORCE_STOP) forceStops.currentStartAfterForceStop()?.let { store.markStartHandled(it) }
    }

    private fun forceStopPending(): Boolean = forceStops.currentStartAfterForceStop().let { it != null && it != store.handledStart() }

    private fun playServicesProblem(): Boolean {
        val state = provider.state.value as? ProviderState.Unavailable ?: return false
        return state.reason in PLAY_REASONS
    }

    private fun notificationsEnabled(): Boolean = try {
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    } catch (_: RuntimeException) {
        false
    }

    private companion object {
        const val MAX_SHEET_STEPS = 3
        const val BANNER_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        val PLAY_REASONS = setOf(
            UnavailableReason.PLAY_SERVICES_MISSING,
            UnavailableReason.PLAY_SERVICES_UPDATE_REQUIRED,
            UnavailableReason.PLAY_SERVICES_DISABLED,
            UnavailableReason.PLAY_SERVICES_INVALID
        )
    }
}
