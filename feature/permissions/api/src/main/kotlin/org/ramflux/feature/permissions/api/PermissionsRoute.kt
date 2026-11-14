// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.permissions.api
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
@Serializable enum class PermissionEntry { SETTINGS, CAMERA_SCAN, CAMERA_PHOTO, AFTER_ACCOUNT_CREATION, MICROPHONE, INCOMING_CALL, ATTACHMENTS }
/** AFTER_ACCOUNT_CREATION is a trusted internal completion hook, never login/launch. */
@Serializable data class PermissionsRoute(val entry: PermissionEntry = PermissionEntry.SETTINGS, val createdAccountId: String? = null) : NavKey
