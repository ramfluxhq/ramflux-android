// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.ramflux.data.*
import org.ramflux.feature.permissions.api.PermissionEntry

@HiltViewModel
class PermissionsViewModel @Inject constructor(private val repository: OsPermissionsRepository) : ViewModel() {
    private val local = MutableStateFlow(PermissionsState())
    val state = local.asStateFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)
    private val channel = Channel<PermissionsEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    private var read: Job? = null
    private var action: Job? = null
    private var pendingRequest: OsPermission? = null
    private var readySent = false
    private var createdAccountId: String? = null
    private var opened = false
    private var generation = 0L
    private var visible = true
    private var active = true
    private var started = false
    fun isCurrent() = active && visible
    fun consumeRequest(event: PermissionsEvent.Request): Boolean {
        if (!active || !visible || event.generation != generation ||
            pendingRequest != event.permission || !local.value.waiting
        ) {
            return false
        }
        pendingRequest = null
        return true
    }
    fun onAction(intent: PermissionsAction) {
        if (!active && intent != PermissionsAction.Back) return
        when (intent) {
            is PermissionsAction.Visible -> {
                visible = intent.visible
                if (visible && opened) {
                    refresh()
                } else if (!visible) {
                    generation++
                    read?.cancel()
                    action?.cancel()
                    pendingRequest = null
                    local.update { it.copy(explanation = null, waiting = false) }
                }
            }
            is PermissionsAction.Open -> if (!opened) {
                opened = true
                local.update { it.copy(entry = intent.entry) }
                createdAccountId = intent.createdAccountId
                refresh()
            }
            PermissionsAction.Refresh -> if (active && opened) refresh()
            is PermissionsAction.Channels -> viewModelScope.launch {
                if (repository.createChannels(intent.labels) is OsPermissionResult.Failed) local.update { it.copy(failed = true) }
            }
            is PermissionsAction.Select -> {
                if (local.value.waiting || action?.isActive == true || local.value.loading) return
                if (intent.permission == OsPermission.BATTERY && local.value.entry == PermissionEntry.SETTINGS) {
                    local.update { it.copy(explanation = OsPermission.BATTERY, failed = false, problem = null) }
                } else {
                    onAction(PermissionsAction.Settings)
                }
            }
            PermissionsAction.Continue -> confirm()
            PermissionsAction.Cancel -> local.update { it.copy(explanation = null, needed = entryNeedsCamera()) }
            PermissionsAction.Back -> {
                active = false
                generation++
                read?.cancel()
                action?.cancel()
                local.value = PermissionsState(loading = false)
                event(PermissionsEvent.Back)
            }
            PermissionsAction.SystemFinished -> {
                local.update { it.copy(waiting = false) }
                if (active) refresh()
            }
            PermissionsAction.BatterySettingsUnavailable -> local.update {
                it.copy(waiting = false, failed = false, problem = PermissionsProblem.BATTERY_SETTINGS_UNAVAILABLE)
            }
            PermissionsAction.SystemFailed -> local.update { it.copy(waiting = false, failed = true) }
            PermissionsAction.Settings -> if (!local.value.waiting) {
                local.update { it.copy(waiting = true, problem = null) }
                event(PermissionsEvent.OpenSettings)
            }
            is PermissionsAction.Pick -> if (local.value.entry == PermissionEntry.ATTACHMENTS && !local.value.waiting) {
                local.update { it.copy(waiting = true, selected = false) }
                event(PermissionsEvent.Pick(intent.photo))
            }
            is PermissionsAction.Picked -> local.update { it.copy(waiting = false, selected = intent.valid) }
        }
    }
    private fun refresh() {
        if (read?.isActive == true || !active || !visible) return
        val ticket = generation
        local.update { it.copy(loading = true) }
        read = viewModelScope.launch {
            when (val result = repository.read()) {
                is OsPermissionResult.Ready -> {
                    if (!active || !visible || ticket != generation) return@launch
                    local.update { it.copy(rows = result.value.toPersistentList(), loading = false, failed = false) }
                    if (!started) {
                        started = true
                        val entry = local.value.entry
                        when (entry) {
                            PermissionEntry.CAMERA_SCAN, PermissionEntry.CAMERA_PHOTO -> camera()
                            PermissionEntry.AFTER_ACCOUNT_CREATION -> if (createdAccountId != null) {
                                val claimed = repository.claimCreatedAccountExplanation(createdAccountId.orEmpty())
                                if (!active || !visible || ticket != generation) return@launch
                                if (claimed is OsPermissionResult.Ready && claimed.value &&
                                    status(OsPermission.NOTIFICATIONS) == OsPermissionStatus.NOT_ASKED
                                ) {
                                    local.update { it.copy(explanation = OsPermission.NOTIFICATIONS) }
                                } else if (claimed is OsPermissionResult.Failed) {
                                    started = false
                                    local.update { it.copy(failed = true) }
                                }
                            }
                            PermissionEntry.MICROPHONE, PermissionEntry.INCOMING_CALL -> local.update { it.copy(unavailable = true) }
                            else -> Unit
                        }
                    } else if (entryNeedsCamera() && !local.value.waiting) {
                        local.update { it.copy(needed = status(OsPermission.CAMERA) != OsPermissionStatus.ALLOWED) }
                        if (!local.value.needed) ready()
                    }
                }
                OsPermissionResult.Failed -> if (active && ticket == generation) local.update { it.copy(loading = false, failed = true) }
            }
        }
    }
    private fun entryNeedsCamera() = local.value.entry in setOf(PermissionEntry.CAMERA_SCAN, PermissionEntry.CAMERA_PHOTO)
    private fun status(permission: OsPermission) = local.value.rows.find { it.permission == permission }?.status
    private fun camera() {
        when (status(OsPermission.CAMERA)) {
            OsPermissionStatus.NOT_ASKED -> local.update { it.copy(explanation = OsPermission.CAMERA) }
            OsPermissionStatus.ALLOWED -> ready()
            else -> local.update { it.copy(needed = true) }
        }
    }
    private fun confirm() {
        val permission = local.value.explanation ?: return
        if (action?.isActive == true || local.value.waiting || !active) return
        local.update { it.copy(explanation = null, waiting = true) }
        val ticket = generation
        action = viewModelScope.launch {
            val claim = if (permission == OsPermission.BATTERY) OsPermissionResult.Ready(true) else repository.claimPrompt(permission)
            if (!active || !visible || ticket != generation) return@launch
            when (claim) {
                is OsPermissionResult.Ready -> if (claim.value) {
                    pendingRequest = permission
                    event(PermissionsEvent.Request(permission, ticket))
                } else {
                    local.update { it.copy(waiting = false, needed = true) }
                    refresh()
                }
                OsPermissionResult.Failed -> local.update { it.copy(waiting = false, failed = true) }
            }
        }
    }
    override fun onCleared() {
        active = false
        generation++
        super.onCleared()
    }
    private fun ready() {
        if (!readySent && visible) {
            readySent = true
            event(PermissionsEvent.Ready)
        }
    }
    private fun event(event: PermissionsEvent) {
        viewModelScope.launch { channel.send(event) }
    }
}
