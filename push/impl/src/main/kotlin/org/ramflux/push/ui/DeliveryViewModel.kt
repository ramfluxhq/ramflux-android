// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ramflux.push.api.IntentCandidate
import org.ramflux.push.api.PushProvider
import org.ramflux.push.guidance.DeliveryGuidance
import org.ramflux.push.guidance.DeliveryPage
import org.ramflux.push.selftest.LocalSelfTest
import org.ramflux.push.settings.SafeSettingsLauncher
import org.ramflux.push.settings.SettingsTier
import org.ramflux.push.store.DeliveryLocalStore

@HiltViewModel
class DeliveryViewModel @Inject constructor(
    private val guidance: DeliveryGuidance,
    private val launcher: SafeSettingsLauncher,
    private val store: DeliveryLocalStore,
    private val selfTest: LocalSelfTest,
    private val provider: PushProvider
) : ViewModel() {
    private val local = MutableStateFlow(DeliveryState(provider = provider.state.value))
    val state: StateFlow<DeliveryState> = local.asStateFlow()
    private var candidates: Map<String, List<IntentCandidate>> = emptyMap()

    init {
        viewModelScope.launch { provider.state.collect { value -> local.update { it.copy(provider = value) } } }
    }

    /** Re-reads every setting; the page calls it each time it comes back to the front. */
    fun refresh() {
        provider.refreshState()
        viewModelScope.launch {
            val page: DeliveryPage = withContext(Dispatchers.Default) { guidance.read() }
            candidates = page.candidates
            local.update {
                it.copy(
                    loading = false,
                    lastWakeMs = store.lastWakeMs(),
                    brandLabel = page.brandLabel,
                    skinHint = page.skinHint,
                    osRelease = page.osRelease,
                    entries = page.entries.toPersistentList()
                )
            }
        }
    }

    fun confirm(id: String) {
        store.confirm(id, System.currentTimeMillis())
        refresh()
    }

    fun undo(id: String) {
        store.unconfirm(id)
        refresh()
    }

    /** Starts from a person's tap. Records only the tier that opened, never the screen. */
    fun open(context: Context, id: String) {
        val list = candidates[id].orEmpty()
        val tier = launcher.launch(context, list)
        store.countOpen(id, tier)
        if (launcher.hasVendorScreen(list) && tier != SettingsTier.VENDOR) {
            local.update { it.copy(cannotOpen = (it.cannotOpen + id).toPersistentSet()) }
        }
    }

    /** Posts a generic message notification after a short delay, so the phone can be locked first. */
    fun startMessageTest() = runTest(MESSAGE_DELAY_MS, selfTest::postMessage) { phase -> copy(messageTest = phase) }

    /** Rings a test call after a longer delay; on Android 14 and later it covers the lock screen only when allowed. */
    fun startCallTest() = runTest(CALL_DELAY_MS, selfTest::postCall) { phase -> copy(callTest = phase) }

    fun answerMessageTest(seen: Boolean) = local.update { it.copy(messageTest = if (seen) TestPhase.PASSED else TestPhase.FAILED) }

    fun answerCallTest(seen: Boolean) = local.update { it.copy(callTest = if (seen) TestPhase.PASSED else TestPhase.FAILED) }

    private fun runTest(delayMs: Long, post: () -> Boolean, set: DeliveryState.(TestPhase) -> DeliveryState) {
        local.update { it.set(TestPhase.WAITING) }
        viewModelScope.launch {
            delay(delayMs)
            val posted = post()
            local.update { it.set(if (posted) TestPhase.ASK else TestPhase.BLOCKED) }
        }
    }

    fun openAppSettings(context: Context) {
        store.countOpen(APP_DETAILS_ID, launcher.launch(context, emptyList()))
    }

    private companion object {
        const val APP_DETAILS_ID = "app_details"
        const val MESSAGE_DELAY_MS = 5_000L
        const val CALL_DELAY_MS = 10_000L
    }
}
