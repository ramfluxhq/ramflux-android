// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions
import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.*
import org.ramflux.data.*
import org.ramflux.feature.permissions.api.PermissionEntry
@Immutable data class PermissionsState(
    val entry: PermissionEntry = PermissionEntry.SETTINGS,
    val rows: ImmutableList<OsPermissionRow> = persistentListOf(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val explanation: OsPermission? = null,
    val waiting: Boolean = false,
    val needed: Boolean = false,
    val unavailable: Boolean = false,
    val selected: Boolean = false
)
sealed interface PermissionsAction {
    data class Open(val entry: PermissionEntry, val createdAccountId: String?) : PermissionsAction
    data class Visible(val visible: Boolean) : PermissionsAction
    data object Refresh : PermissionsAction
    data class Channels(val labels: NotificationChannelLabels) : PermissionsAction
    data class Select(val permission: OsPermission) : PermissionsAction
    data object Continue : PermissionsAction
    data object Cancel : PermissionsAction
    data object Back : PermissionsAction
    data object SystemFinished : PermissionsAction
    data object SystemFailed : PermissionsAction
    data object Settings : PermissionsAction
    data class Pick(val photo: Boolean) : PermissionsAction
    data class Picked(val valid: Boolean) : PermissionsAction
}
sealed interface PermissionsEvent {
    data object Back : PermissionsEvent
    data object OpenSettings : PermissionsEvent
    data class Request(val permission: OsPermission, val generation: Long) : PermissionsEvent
    data class Pick(val photo: Boolean) : PermissionsEvent
    data object Ready : PermissionsEvent
}
