// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.ramflux.data.MlsAdminKind
import org.ramflux.data.MlsAdminRules
import org.ramflux.data.MlsGroupSession
import org.ramflux.data.MlsNameError
import org.ramflux.data.admin
import org.ramflux.data.setApproval
import org.ramflux.data.setName

/**
 * The group settings: the name (trimmed, 1 to 64 grapheme clusters, Save disabled while unchanged) and the approval switch.
 * Both show the confirmed value from info until the `settings` event arrives, with "Saving…". The name Save and turning
 * approval on are their own confirmation; turning approval off opens a confirmation first (A1). One field per call: a settings
 * call never carries the push mode (A14). W: a `@HiltViewModel` constructor.
 */
class MlsSettingsViewModel(private val session: MlsGroupSession) : ViewModel() {
    private class Ids(val account: String, val group: String)

    private val clock: () -> Long = System::currentTimeMillis
    private var current: Ids? = null
    private var collector: Job? = null
    private var lockWatch: Job? = null
    private val draft = MutableStateFlow<String?>(null)
    private val confirmOff = MutableStateFlow(false)
    private val channel = Channel<MlsSettingsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private val shown = MutableStateFlow(MlsSettingsUi())
    val state: StateFlow<MlsSettingsUi> = shown

    fun onAction(action: MlsSettingsAction) {
        val ids = current
        when (action) {
            is MlsSettingsAction.Open -> open(action.account, action.groupId)
            is MlsSettingsAction.Draft -> draft.value = action.text
            MlsSettingsAction.Save -> if (ids != null && shown.value.canSave) session.setName(ids.account, ids.group, draft.value.orEmpty())
            is MlsSettingsAction.Approval -> {
                val now = shown.value
                if (ids != null && now.canEdit && !now.busy && !now.paced && now.approval != action.on) {
                    if (action.on) session.setApproval(ids.account, ids.group, true) else confirmOff.value = true
                }
            }
            MlsSettingsAction.ConfirmApprovalOff -> {
                confirmOff.value = false
                if (ids != null) session.setApproval(ids.account, ids.group, false)
            }
            MlsSettingsAction.CancelApprovalOff -> confirmOff.value = false
            MlsSettingsAction.Back -> channel.trySend(MlsSettingsEvent.Back)
        }
    }

    private fun open(account: String, group: String) {
        if (current?.account == account && current?.group == group) return
        current = Ids(account, group)
        session.watch(account)
        session.loadInfo(account, group)
        lockWatch?.cancel()
        lockWatch = dropOnLock(session, account) {
            // The unsaved name, the approval-off confirmation and the failure line go with the lock; the screen closes.
            collector?.cancel()
            draft.value = null
            confirmOff.value = false
            shown.value = MlsSettingsUi()
            current = null
            channel.trySend(MlsSettingsEvent.Back)
        }
        collector?.cancel()
        collector = viewModelScope.launch {
            combine(
                session.info(account, group),
                session.timeline(account, group),
                session.admin(account, group),
                draft,
                confirmOff
            ) { info, timeline, admin, text, ask ->
                val g = info.group
                val canEdit = g != null && timeline.readOnly == null && MlsAdminRules.isAdmin(g.myRole)
                val now = clock()
                val paced = admin.pacedUntil?.let { it > now } == true
                val trimmed = (text ?: g?.name).orEmpty().trim()
                val error = when {
                    text == null -> null
                    trimmed.isEmpty() -> MlsNameError.REQUIRED
                    MlsGroupSession.graphemes(trimmed) > MlsGroupSession.NAME_MAX -> MlsNameError.TOO_LONG
                    else -> null
                }
                val action = admin.slot ?: admin.pending?.action
                val failure = admin.lastFailure?.takeIf { it.kind == MlsAdminKind.SETTINGS }?.failure
                MlsSettingsUi(
                    name = g?.name,
                    draft = text ?: g?.name.orEmpty(),
                    nameError = error,
                    canSave = canEdit && text != null && error == null && trimmed != g.name && !admin.busy && !paced,
                    nameSaving = action?.kind == MlsAdminKind.SETTINGS && action.target == "name",
                    approval = g?.approval ?: false,
                    approvalKnown = g != null,
                    approvalSaving = action?.kind == MlsAdminKind.SETTINGS && action.target == "approval",
                    confirmApprovalOff = ask,
                    canEdit = canEdit,
                    busy = admin.busy,
                    paced = paced,
                    pacedUntil = admin.pacedUntil,
                    loading = info.loading && g == null,
                    failure = failure
                )
            }.collect { shown.value = it }
        }
    }
}
