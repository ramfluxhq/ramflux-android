// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.push.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitButtonSize
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.push.api.ProviderState
import org.ramflux.push.api.UnavailableReason
import org.ramflux.push.guidance.DeliveryEntry
import org.ramflux.push.guidance.EntryGroup
import org.ramflux.push.guidance.EntryLabel
import org.ramflux.push.impl.R

@Composable
fun DeliveryScreen(
    state: DeliveryState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onUndo: (String) -> Unit,
    onOpenAppSettings: () -> Unit,
    selfTest: SelfTestActions,
    modifier: Modifier = Modifier
) {
    val theme = LocalRFTheme.current
    Column(modifier.fillMaxSize()) {
        KitPageHeader(stringResource(R.string.delivery_title), onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = theme.gap.dp).testTag("delivery-body"),
            verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)
        ) {
            Text(stringResource(R.string.delivery_intro), color = theme.text)
            Section(R.string.delivery_section_status)
            Text(providerText(state), color = theme.text, modifier = Modifier.testTag("delivery-status"))
            Text(lastWakeText(state.lastWakeMs), color = theme.text)
            Section(R.string.delivery_section_phone)
            val brand = state.brandLabel
            if (brand != null) {
                Text(stringResource(R.string.delivery_phone_summary, stringResource(brand), state.osRelease), color = theme.text)
                state.skinHint?.let { Text(stringResource(it), color = theme.text) }
            }
            Entries(state, EntryGroup.PHONE, onOpen, onConfirm, onUndo, onOpenAppSettings)
            Section(R.string.delivery_section_calls)
            Entries(state, EntryGroup.CALLS, onOpen, onConfirm, onUndo, onOpenAppSettings)
            if (state.entries.any { it.group == EntryGroup.BRAND }) {
                Section(R.string.delivery_section_brand)
                Entries(state, EntryGroup.BRAND, onOpen, onConfirm, onUndo, onOpenAppSettings)
            }
            Section(R.string.delivery_section_selftest)
            SelfTest(state, selfTest)
        }
    }
}

@Composable
private fun Section(title: Int) {
    Text(stringResource(title), color = LocalRFTheme.current.text)
}

@Composable
private fun Entries(
    state: DeliveryState,
    group: EntryGroup,
    onOpen: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onUndo: (String) -> Unit,
    onOpenAppSettings: () -> Unit
) {
    val theme = LocalRFTheme.current
    for (entry in state.entries.filter { it.group == group }) {
        Column(Modifier.fillMaxWidth().testTag("delivery-" + entry.id)) {
            KitRow(
                stringResource(entry.title),
                onClick = { if (entry.canOpen) onOpen(entry.id) },
                value = stringResource(labelText(entry.label)),
                trailing = KitRowTrailing.None,
                enabled = entry.canOpen,
                testTag = "delivery-row-" + entry.id
            )
            Text(stringResource(entry.body), color = theme.text)
            if (entry.id in state.cannotOpen) {
                Text(stringResource(R.string.delivery_cannot_open), color = theme.text)
                KitButton(
                    stringResource(R.string.delivery_open_app_settings),
                    onOpenAppSettings,
                    kind = KitButtonKind.Secondary,
                    size = KitButtonSize.Compact
                )
            }
            EntryButtons(entry, onOpen, onConfirm, onUndo)
        }
    }
}

@Composable
private fun EntryButtons(entry: DeliveryEntry, onOpen: (String) -> Unit, onConfirm: (String) -> Unit, onUndo: (String) -> Unit) {
    val theme = LocalRFTheme.current
    if (!entry.canOpen && !entry.canConfirm) return
    if (entry.label == EntryLabel.ON) return
    Row(horizontalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
        if (entry.canOpen) {
            KitButton(
                stringResource(R.string.delivery_open_settings),
                { onOpen(entry.id) },
                kind = KitButtonKind.Secondary,
                size = KitButtonSize.Compact,
                tag = "delivery-open-" + entry.id
            )
        }
        if (entry.canConfirm) {
            if (entry.label == EntryLabel.MARKED_DONE) {
                KitButton(
                    stringResource(R.string.delivery_undo),
                    { onUndo(entry.id) },
                    kind = KitButtonKind.Text,
                    tag = "delivery-undo-" + entry.id
                )
            } else {
                KitButton(
                    stringResource(R.string.delivery_done),
                    { onConfirm(entry.id) },
                    kind = KitButtonKind.Text,
                    tag = "delivery-done-" + entry.id
                )
            }
        }
    }
}

private fun labelText(label: EntryLabel): Int = when (label) {
    EntryLabel.ON -> R.string.delivery_label_on
    EntryLabel.OFF_LATE -> R.string.delivery_label_off_late
    EntryLabel.NOT_CHECKED -> R.string.delivery_label_not_checked
    EntryLabel.MARKED_DONE -> R.string.delivery_label_marked_done
    EntryLabel.EVIDENCE_PROBLEM -> R.string.delivery_label_evidence
}

@Composable
private fun providerText(state: DeliveryState): String = when (val provider = state.provider) {
    ProviderState.Ready -> stringResource(R.string.delivery_status_ready)
    is ProviderState.Unavailable -> stringResource(
        when (provider.reason) {
            UnavailableReason.NOT_CONFIGURED -> R.string.delivery_status_not_configured
            UnavailableReason.PLAY_SERVICES_MISSING -> R.string.delivery_status_play_missing
            UnavailableReason.PLAY_SERVICES_UPDATE_REQUIRED -> R.string.delivery_status_play_update
            UnavailableReason.PLAY_SERVICES_DISABLED -> R.string.delivery_status_play_disabled
            UnavailableReason.PLAY_SERVICES_INVALID -> R.string.delivery_status_play_invalid
            UnavailableReason.TOKEN_FAILED -> R.string.delivery_status_token_failed
            UnavailableReason.NODE_REFUSED -> R.string.delivery_status_node_refused
            UnavailableReason.CENTRE_UNAVAILABLE -> R.string.delivery_status_centre_unavailable
        }
    )
}

@Composable
private fun lastWakeText(lastWakeMs: Long): String = if (lastWakeMs <= 0L) {
    stringResource(R.string.delivery_no_wake)
} else {
    stringResource(
        R.string.delivery_last_wake,
        DateUtils.getRelativeTimeSpanString(lastWakeMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    )
}

/** What the self-test section can do. */
class SelfTestActions(
    val startMessage: () -> Unit,
    val answerMessage: (Boolean) -> Unit,
    val startCall: () -> Unit,
    val answerCall: (Boolean) -> Unit
)

@Composable
private fun SelfTest(state: DeliveryState, actions: SelfTestActions) {
    val theme = LocalRFTheme.current
    val needsAttention = state.entries.any { it.required && it.label == EntryLabel.OFF_LATE }
    Text(
        stringResource(if (needsAttention) R.string.delivery_selftest_settings_attention else R.string.delivery_selftest_settings_ok),
        color = theme.text
    )
    TestBlock(
        state.messageTest,
        R.string.delivery_selftest_message_title,
        R.string.delivery_selftest_message_waiting,
        R.string.delivery_selftest_message_ask,
        actions.startMessage,
        actions.answerMessage,
        "message"
    )
    TestBlock(
        state.callTest,
        R.string.delivery_selftest_call_title,
        R.string.delivery_selftest_call_waiting,
        R.string.delivery_selftest_call_ask,
        actions.startCall,
        actions.answerCall,
        "call"
    )
}

@Composable
private fun TestBlock(phase: TestPhase, title: Int, waiting: Int, ask: Int, start: () -> Unit, answer: (Boolean) -> Unit, tag: String) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxWidth().testTag("delivery-test-" + tag), verticalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
        Text(stringResource(title), color = theme.text)
        when (phase) {
            TestPhase.IDLE -> KitButton(
                stringResource(R.string.delivery_selftest_start),
                start,
                kind = KitButtonKind.Secondary,
                size = KitButtonSize.Compact,
                tag = "delivery-test-start-" + tag
            )
            TestPhase.WAITING -> Text(stringResource(waiting), color = theme.text)
            TestPhase.ASK -> {
                Text(stringResource(ask), color = theme.text)
                Row(horizontalArrangement = Arrangement.spacedBy((theme.gap / 2).dp)) {
                    KitButton(
                        stringResource(R.string.delivery_selftest_yes),
                        { answer(true) },
                        kind = KitButtonKind.Secondary,
                        size = KitButtonSize.Compact,
                        tag = "delivery-test-yes-" + tag
                    )
                    KitButton(
                        stringResource(R.string.delivery_selftest_no),
                        { answer(false) },
                        kind = KitButtonKind.Secondary,
                        size = KitButtonSize.Compact,
                        tag = "delivery-test-no-" + tag
                    )
                }
            }
            TestPhase.PASSED -> TestResult(R.string.delivery_selftest_passed, R.string.delivery_selftest_again, start, tag)
            TestPhase.FAILED -> TestResult(R.string.delivery_selftest_failed, R.string.delivery_selftest_again, start, tag)
            TestPhase.BLOCKED -> TestResult(R.string.delivery_selftest_blocked, R.string.delivery_selftest_again, start, tag)
        }
    }
}

@Composable
private fun TestResult(text: Int, again: Int, start: () -> Unit, tag: String) {
    Text(stringResource(text), color = LocalRFTheme.current.text)
    KitButton(stringResource(again), start, kind = KitButtonKind.Text, tag = "delivery-test-again-" + tag)
}
