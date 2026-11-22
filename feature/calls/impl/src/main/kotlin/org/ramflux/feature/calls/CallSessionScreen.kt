// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.KitAvatar
import org.ramflux.designsystem.kit.KitAvatarKind
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitIconButton
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.UntrustedText
import org.ramflux.designsystem.render
import org.ramflux.feature.calls.impl.R

/** The call destination. [onMicrophonePermission] opens the permissions feature's microphone flow. */
@Composable
fun CallSessionNavigation(
    accountId: String,
    conversationId: String?,
    callId: String?,
    onClose: () -> Unit,
    onMicrophonePermission: () -> Unit,
    modifier: Modifier = Modifier,
    vm: CallSessionViewModel = hiltViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val close by rememberUpdatedState(onClose)
    val permission by rememberUpdatedState(onMicrophonePermission)
    val context = LocalContext.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    LaunchedEffect(
        vm,
        accountId,
        conversationId,
        callId
    ) { vm.onAction(CallSessionAction.Load(accountId, conversationId, callId, granted())) }
    // Coming back from the permission flow re-reads the grant.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onAction(CallSessionAction.Load(accountId, conversationId, callId, granted())) }
    LaunchedEffect(vm) {
        vm.events.collect {
            when (it) {
                CallSessionEvent.Permission -> permission()
                CallSessionEvent.Close -> close()
            }
        }
    }
    BackHandler { vm.onAction(CallSessionAction.Close) }
    CallSessionScreen(state, vm::onAction, modifier)
}

@Composable
fun CallSessionScreen(state: CallSessionState, onAction: (CallSessionAction) -> Unit, modifier: Modifier = Modifier) {
    val theme = LocalRFTheme.current
    Column(
        modifier.fillMaxSize().background(theme.bg).padding(theme.gap.dp).testTag("call-session"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(theme.gap.dp)
    ) {
        Spacer(Modifier.weight(1f))
        val peer = state.peer
        KitAvatar(peer.firstOrNull()?.uppercase() ?: "", AVATAR, KitAvatarKind.Person)
        if (peer.isNotBlank()) UntrustedText(peer, kind = UntrustedKind.Name)
        KitMeta(stringResource(stageText(state.stage)), centered = true, tag = "call-stage")
        if (state.mediaUnavailable) {
            KitTipBar(stringResource(R.string.call_media_unavailable), "mic-off", Modifier.testTag("call-media-unavailable"))
        }
        state.problem?.let { KitTipBar(it.render(), "circle-alert") }
        Spacer(Modifier.weight(1f))
        Controls(state, onAction)
    }
}

@Composable
private fun Controls(state: CallSessionState, onAction: (CallSessionAction) -> Unit) {
    val theme = LocalRFTheme.current
    val wide = Modifier.fillMaxWidth()
    when (state.stage) {
        CallStage.PERMISSION -> KitButton(
            stringResource(R.string.call_allow_microphone),
            { onAction(CallSessionAction.AskPermission) },
            wide,
            tag = "call-permission"
        )
        CallStage.INCOMING -> Row(wide, horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
            KitButton(
                stringResource(R.string.call_decline),
                { onAction(CallSessionAction.Decline) },
                Modifier.weight(1f),
                kind = KitButtonKind.Destructive,
                enabled = !state.busy,
                tag = "call-decline"
            )
            KitButton(
                stringResource(R.string.call_answer),
                { onAction(CallSessionAction.Answer) },
                Modifier.weight(1f),
                enabled = !state.busy,
                tag = "call-answer"
            )
        }
        CallStage.OUTGOING, CallStage.CONNECTING, CallStage.IN_CALL -> {
            // Mute, speaker and camera need the media engine; with the refusing engine they stay off.
            val media = !state.mediaUnavailable && state.stage == CallStage.IN_CALL
            Row(horizontalArrangement = Arrangement.spacedBy(theme.gap.dp)) {
                KitIconButton("mic-off", stringResource(R.string.call_mute), {}, enabled = media, tag = "call-mute")
                KitIconButton("volume-2", stringResource(R.string.call_speaker), {}, enabled = media, tag = "call-speaker")
                KitIconButton("switch-camera", stringResource(R.string.call_camera), {}, enabled = media, tag = "call-camera")
            }
            KitButton(
                stringResource(R.string.call_hang_up),
                { onAction(CallSessionAction.HangUp) },
                wide,
                kind = KitButtonKind.Destructive,
                enabled = !state.busy,
                tag = "call-hang-up"
            )
        }
        CallStage.FAILED, CallStage.OFFLINE, CallStage.CONNECTION_LOST -> {
            KitButton(stringResource(R.string.call_retry), { onAction(CallSessionAction.Retry) }, wide, tag = "call-retry")
            KitButton(
                stringResource(R.string.call_close),
                { onAction(CallSessionAction.Close) },
                wide,
                kind = KitButtonKind.Secondary,
                tag = "call-close"
            )
        }
        CallStage.STARTING, CallStage.ENDED -> KitButton(
            stringResource(R.string.call_close),
            { onAction(CallSessionAction.Close) },
            wide,
            kind = KitButtonKind.Secondary,
            tag = "call-close"
        )
    }
}

private fun stageText(stage: CallStage) = when (stage) {
    CallStage.PERMISSION -> R.string.call_stage_permission
    CallStage.STARTING -> R.string.call_stage_starting
    CallStage.OUTGOING -> R.string.call_stage_outgoing
    CallStage.INCOMING -> R.string.call_stage_incoming
    CallStage.CONNECTING -> R.string.call_stage_connecting
    CallStage.IN_CALL -> R.string.call_stage_in_call
    CallStage.CONNECTION_LOST -> R.string.call_stage_connection_lost
    CallStage.OFFLINE -> R.string.call_stage_offline
    CallStage.FAILED -> R.string.call_stage_failed
    CallStage.ENDED -> R.string.call_stage_ended
}

private const val AVATAR = 96f
