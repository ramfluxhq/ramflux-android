// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data

object OsPickerMimeTypes { const val FILES = "*/*" }
enum class OsPermission { NOTIFICATIONS, MICROPHONE, CAMERA, INCOMING_CALL, BATTERY, SAVE_PHOTO, PHOTOS_FILES }
enum class OsPermissionStatus { ALLOWED, DENIED, NOT_ASKED, NOT_REQUIRED, UNAVAILABLE }
data class OsPermissionRow(val permission: OsPermission, val status: OsPermissionStatus)
data class NotificationChannelLabels(val messages: String, val calls: String, val system: String)
sealed interface OsPermissionResult<out T> {
    data class Ready<T>(val value: T) : OsPermissionResult<T>
    data object Failed : OsPermissionResult<Nothing>
}
/** Local OS state only. It has no transport, account or synchronization dependency. */
interface OsPermissionsRepository {
    suspend fun read(): OsPermissionResult<List<OsPermissionRow>>
    suspend fun createChannels(labels: NotificationChannelLabels): OsPermissionResult<Unit>
    /** Durable claim before issuing a one-shot request; never re-prompt automatically. */
    suspend fun claimPrompt(permission: OsPermission): OsPermissionResult<Boolean>
    /** Once per created account, not once per startup/login. Cancel consumes this explanation too. */
    suspend fun claimCreatedAccountExplanation(accountId: String): OsPermissionResult<Boolean>
}
