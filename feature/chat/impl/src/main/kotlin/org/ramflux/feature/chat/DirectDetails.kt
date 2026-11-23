// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.ramflux.data.ClientPhase
import org.ramflux.data.ClientResult
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.ContactDirectoryRepository
import org.ramflux.data.MediaRepository
import org.ramflux.data.MessageHistoryRepository
import org.ramflux.data.SafetyState
import org.ramflux.data.UnavailableMediaRepository
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitPageHeader
import org.ramflux.designsystem.kit.KitRow
import org.ramflux.designsystem.kit.KitRowTrailing
import org.ramflux.designsystem.kit.KitSwitch
import org.ramflux.designsystem.kit.KitTipBar
import org.ramflux.designsystem.kit.LtrCode
import org.ramflux.designsystem.render
import org.ramflux.designsystem.uiText
import org.ramflux.feature.chat.impl.R

/** The details of a direct conversation: the contact, mute, the safety number, media and storage. */
@Immutable data class DirectDetailsState(
    val loading: Boolean = true,
    /** Peer-controlled: the contact's name, or the chat's title when no contact matches. */
    val name: String = "",
    /** Null when the directory row does not report it. */
    val muted: Boolean? = null,
    val muting: Boolean = false,
    /** The contact's safety number; null when no contact matches the route or the SDK did not return one. */
    val safetyNumber: String? = null,
    val safety: SafetyState? = null,
    /** False when the conversation names no peer the safety number could be read for. */
    val peerKnown: Boolean = false,
    /** Decrypted media of this conversation held on this phone, in bytes. */
    val storageBytes: Long? = null,
    val confirmClear: Boolean = false,
    val problem: UiText? = null
)

sealed interface DirectDetailsAction {
    data class SetMuted(val muted: Boolean) : DirectDetailsAction
    data object ClearRequested : DirectDetailsAction
    data object ClearCancelled : DirectDetailsAction
    data object ClearConfirmed : DirectDetailsAction
}

@HiltViewModel
class DirectDetailsViewModel @Inject constructor(
    private val history: MessageHistoryRepository,
    private val contacts: ContactDirectoryRepository,
    private val session: ClientSessionRepository,
    private val media: MediaRepository
) : ViewModel() {
    /** For hosts that keep no media (tests and previews). */
    constructor(history: MessageHistoryRepository, contacts: ContactDirectoryRepository, session: ClientSessionRepository) :
        this(history, contacts, session, UnavailableMediaRepository())

    private data class Target(val account: String, val conversation: String, val generation: Long)

    private var target: Target? = null
    private var job: Job? = null
    private val local = MutableStateFlow(DirectDetailsState())
    val state: StateFlow<DirectDetailsState> = local.asStateFlow()

    /** Reads the details of [conversation]; [title] is shown until a contact name is found. */
    fun open(account: String, conversation: String, title: String?) {
        val status = session.status.value
        if (status.phase != ClientPhase.OPEN) return
        val opened = Target(account, conversation, status.generation)
        target = opened
        job?.cancel()
        local.value = DirectDetailsState(name = title.orEmpty())
        job = viewModelScope.launch {
            val header = history.header(account, conversation)
            if (!current(opened)) return@launch
            val facts = (header as? ClientResult.Ready)?.value
            if (facts == null) {
                local.update { it.copy(loading = false, problem = uiText(R.string.direct_details_unavailable)) }
                return@launch
            }
            val peer = facts.peer
            val contact = peer?.let { root ->
                (contacts.contacts(account) as? ClientResult.Ready)?.value?.firstOrNull { it.root == root || it.principal == root }
            }
            val safety = peer?.let { (contacts.safety(account, it) as? ClientResult.Ready)?.value }
            val bytes = usage(account, conversation)
            if (!current(opened)) return@launch
            local.update {
                it.copy(
                    loading = false,
                    name = contact?.name?.takeIf { name -> name.isNotBlank() } ?: it.name,
                    muted = facts.muted,
                    peerKnown = peer != null,
                    safetyNumber = safety?.number?.takeIf { number -> number.isNotBlank() },
                    safety = safety?.state ?: contact?.let { known -> known.safetyState() },
                    storageBytes = bytes
                )
            }
        }
    }

    fun close() {
        job?.cancel()
        target = null
        local.value = DirectDetailsState()
    }

    fun onAction(action: DirectDetailsAction) {
        val opened = target ?: return
        when (action) {
            is DirectDetailsAction.SetMuted -> {
                if (local.value.muting || local.value.muted == null) return
                local.update { it.copy(muting = true, problem = null) }
                viewModelScope.launch {
                    val result = history.setMuted(opened.account, opened.conversation, action.muted)
                    if (!current(opened)) return@launch
                    local.update {
                        when (result) {
                            is ClientResult.Ready -> it.copy(muting = false, muted = result.value)
                            else -> it.copy(muting = false, problem = uiText(R.string.direct_details_mute_failed))
                        }
                    }
                }
            }
            DirectDetailsAction.ClearRequested -> if ((local.value.storageBytes ?: 0L) > 0L) local.update { it.copy(confirmClear = true) }
            DirectDetailsAction.ClearCancelled -> local.update { it.copy(confirmClear = false) }
            DirectDetailsAction.ClearConfirmed -> {
                if (!local.value.confirmClear) return
                local.update { it.copy(confirmClear = false) }
                viewModelScope.launch {
                    media.purgeConversation(opened.account, opened.conversation)
                    val bytes = usage(opened.account, opened.conversation)
                    if (current(opened)) local.update { it.copy(storageBytes = bytes) }
                }
            }
        }
    }

    private suspend fun usage(account: String, conversation: String): Long =
        media.storage(account).conversations.firstOrNull { it.conversation == conversation }?.bytes ?: 0L

    private fun org.ramflux.data.ContactSummary.safetyState() = if (verified) SafetyState.VERIFIED else SafetyState.UNVERIFIED

    private fun current(opened: Target): Boolean =
        target == opened && session.status.value.phase == ClientPhase.OPEN && session.status.value.generation == opened.generation
}

/**
 * The direct-conversation details over the chat (no mobile board exists yet; built from the Kit and the group
 * details and storage boards). Facts the SDK does not return are said to be unavailable.
 */
@Composable
internal fun BoxScope.DirectDetails(
    state: DirectDetailsState,
    onAction: (DirectDetailsAction) -> Unit,
    onGallery: () -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalRFTheme.current
    BackHandler { onClose() }
    Column(Modifier.matchParentSize().background(theme.bg).testTag("direct-details")) {
        KitPageHeader(stringResource(R.string.chat_conversation_details), onBack = onClose, tag = "direct-details-header")
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(theme.gap.dp)) {
            if (state.name.isNotEmpty()) {
                KitRow(state.name, {}, icon = "user-round", trailing = KitRowTrailing.None, peer = true, testTag = "direct-details-name")
            }
            if (state.loading) KitMeta(stringResource(R.string.direct_details_loading), Modifier.fillMaxWidth())
            state.problem?.let { KitTipBar(it.render(), "circle-alert", Modifier.padding(vertical = theme.gap.dp)) }
            if (!state.loading) {
                val muted = state.muted
                if (muted != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = (theme.gap / 2).dp), verticalAlignment = Alignment.CenterVertically) {
                        KitMeta(stringResource(R.string.direct_details_mute), Modifier.weight(1f))
                        KitSwitch(
                            muted,
                            { onAction(DirectDetailsAction.SetMuted(it)) },
                            enabled = !state.muting,
                            label = stringResource(R.string.direct_details_mute),
                            tag = "direct-details-mute"
                        )
                    }
                }
                KitMeta(stringResource(R.string.direct_details_safety), Modifier.fillMaxWidth().padding(top = theme.gap.dp))
                val number = state.safetyNumber
                when {
                    number != null -> {
                        LtrCode(
                            number,
                            Modifier.fillMaxWidth().padding(vertical = (theme.gap / 2).dp),
                            groupSize = SAFETY_GROUP,
                            tag = "direct-details-safety-number"
                        )
                        KitMeta(safetyText(state.safety), Modifier.fillMaxWidth().testTag("direct-details-safety-state"))
                    }
                    state.peerKnown -> KitMeta(stringResource(R.string.direct_details_safety_unavailable), Modifier.fillMaxWidth())
                    else -> KitMeta(stringResource(R.string.direct_details_safety_no_peer), Modifier.fillMaxWidth())
                }
            }
            KitRow(stringResource(R.string.gallery_title), onGallery, icon = "images", testTag = "direct-details-gallery")
            state.storageBytes?.let { bytes ->
                val size = android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)
                KitRow(
                    stringResource(R.string.direct_details_storage),
                    {},
                    icon = "hard-drive",
                    value = size,
                    trailing = KitRowTrailing.None,
                    testTag = "direct-details-storage"
                )
                if (bytes > 0L) {
                    if (state.confirmClear) {
                        KitMeta(stringResource(R.string.storage_clear_body), Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth().padding(vertical = theme.gap.dp)) {
                            KitButton(
                                stringResource(R.string.ui_cancel),
                                { onAction(DirectDetailsAction.ClearCancelled) },
                                Modifier.weight(1f),
                                kind = KitButtonKind.Secondary,
                                tag = "direct-details-clear-cancel"
                            )
                            KitButton(
                                stringResource(R.string.storage_clear),
                                { onAction(DirectDetailsAction.ClearConfirmed) },
                                Modifier.weight(1f).padding(start = theme.gap.dp),
                                kind = KitButtonKind.Destructive,
                                tag = "direct-details-clear-confirm"
                            )
                        }
                    } else {
                        KitRow(
                            stringResource(R.string.storage_clear_title),
                            { onAction(DirectDetailsAction.ClearRequested) },
                            icon = "trash-2",
                            testTag = "direct-details-clear"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun safetyText(state: SafetyState?): String = stringResource(
    when (state) {
        SafetyState.VERIFIED -> R.string.direct_details_safety_verified
        SafetyState.CHANGED -> R.string.direct_details_safety_changed
        SafetyState.UNVERIFIED -> R.string.direct_details_safety_unverified
        else -> R.string.direct_details_safety_unknown
    }
)

private const val SAFETY_GROUP = 5
