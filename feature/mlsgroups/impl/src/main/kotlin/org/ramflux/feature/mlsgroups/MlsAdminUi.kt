// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsGroupPush
import org.ramflux.data.MlsGroupWarning
import org.ramflux.data.MlsNameError
import org.ramflux.data.MlsRequestPhase

/*
 * The UI types of the M3 admin screens: join requests, member actions, settings, the push picker and the group info rows.
 * Names, anchors and request ids are held only to dispatch a confirmed action; they are never shown as ids, never put in a
 * test tag and never logged (every type prints `<redacted>`).
 */

// ---- join requests ---------------------------------------------------------------------------------------------------

@Immutable
data class MlsRequestRowUi(
    val requestId: String,
    /** Null for a non-contact: the screen shows "this member", never the anchor. */
    val display: String?,
    val ai: Boolean,
    val requestedAt: Long?,
    val expiresAt: Long?,
    val phase: MlsRequestPhase,
    val failure: MlsFailure?,
    /** The local clock passed `expires_at`: both buttons are disabled and the row leaves on the next list read. */
    val expired: Boolean
) {
    override fun toString() = "MlsRequestRowUi(<redacted>)"
}

enum class MlsRequestConfirmKind { APPROVE, DECLINE }

@Immutable
data class MlsRequestConfirmUi(
    val kind: MlsRequestConfirmKind,
    val requestId: String,
    val display: String?,
    val ai: Boolean,
    val expiresAt: Long?
) {
    override fun toString() = "MlsRequestConfirmUi(<redacted>)"
}

@Immutable
data class MlsRequestsUi(
    val groupName: String? = null,
    val rows: ImmutableList<MlsRequestRowUi> = persistentListOf(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val loadFailure: MlsFailure? = null,
    val pageFailure: MlsFailure? = null,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /** Approval is off, or the role changed: the list is closed and the screen explains it. */
    val approvalOff: Boolean = false,
    /** An admin commit is running or waits for its event: Approve is disabled for every row. */
    val busy: Boolean = false,
    val paced: Boolean = false,
    val pacedUntil: Long? = null,
    val confirm: MlsRequestConfirmUi? = null,
    /** A request that was no longer pending left the list (`request_not_found`). */
    val goneNotice: Boolean = false
) {
    override fun toString() = "MlsRequestsUi(<redacted>)"
}

sealed interface MlsRequestsAction {
    data class Open(val account: String, val groupId: String) : MlsRequestsAction
    data object LoadMore : MlsRequestsAction
    data object Retry : MlsRequestsAction
    data class AskApprove(val requestId: String) : MlsRequestsAction
    data class AskDecline(val requestId: String) : MlsRequestsAction
    data object Confirm : MlsRequestsAction
    data object Cancel : MlsRequestsAction
    data object DismissNotice : MlsRequestsAction
    data object Back : MlsRequestsAction
}

sealed interface MlsRequestsEvent {
    data object Back : MlsRequestsEvent
}

// ---- member actions --------------------------------------------------------------------------------------------------

enum class MlsMemberActionKind(val tag: String) {
    MAKE_ADMIN("make_admin"),
    MAKE_MEMBER("make_member"),
    REMOVE("remove"),
    BAN("ban")
}

@Immutable
data class MlsMemberConfirmUi(val kind: MlsMemberActionKind, val anchor: String, val display: String?, val ai: Boolean) {
    override fun toString() = "MlsMemberConfirmUi(<redacted>)"
}

// ---- settings --------------------------------------------------------------------------------------------------------

@Immutable
data class MlsSettingsUi(
    /** The confirmed name from the last info read; never the requested one. */
    val name: String? = null,
    val draft: String = "",
    val nameError: MlsNameError? = null,
    val canSave: Boolean = false,
    val nameSaving: Boolean = false,
    /** The confirmed value from info, until the event arrives. */
    val approval: Boolean = false,
    val approvalKnown: Boolean = false,
    val approvalSaving: Boolean = false,
    val confirmApprovalOff: Boolean = false,
    /** Owner or admin of a group that is not read-only. */
    val canEdit: Boolean = false,
    val busy: Boolean = false,
    val paced: Boolean = false,
    val pacedUntil: Long? = null,
    val loading: Boolean = false,
    val failure: MlsFailure? = null
) {
    override fun toString() = "MlsSettingsUi(<redacted>)"
}

sealed interface MlsSettingsAction {
    data class Open(val account: String, val groupId: String) : MlsSettingsAction
    data class Draft(val text: String) : MlsSettingsAction
    data object Save : MlsSettingsAction
    data class Approval(val on: Boolean) : MlsSettingsAction
    data object ConfirmApprovalOff : MlsSettingsAction
    data object CancelApprovalOff : MlsSettingsAction
    data object Back : MlsSettingsAction
}

sealed interface MlsSettingsEvent {
    data object Back : MlsSettingsEvent
}

// ---- group info rows -------------------------------------------------------------------------------------------------

/** The push picker: this device's confirmed mode and the one being saved. Shown to every member of a group that is not read-only. */
@Immutable
data class MlsPushUi(val value: MlsGroupPush, val saving: MlsGroupPush?, val failure: MlsFailure?)

/** A quota line of group info (the commit quota). */
@Immutable
data class MlsQuotaLineUi(val remaining: Long, val limit: Long, val firstDay: Boolean, val resetsAt: Long?)

/** The admin rows of group info; each is null or empty for a read-only group (A16). */
@Immutable
data class MlsInfoAdminUi(
    /** Loaded request rows; more than this when [requestsMore]. Null when the entry is not shown. */
    val requestCount: Int? = null,
    val requestsMore: Boolean = false,
    val settingsEntry: Boolean = false,
    val push: MlsPushUi? = null,
    val commitQuota: MlsQuotaLineUi? = null,
    val privacyDelay: Boolean = false,
    /** Warning rows, most severe first. `host_ip_visible` is the M2 row and is not in this list. */
    val warnings: ImmutableList<MlsGroupWarning> = persistentListOf(),
    /** A member of a group that is not read-only sees the admission hint actions on `admission_needs_online`. */
    val hintActions: Boolean = false
) {
    override fun toString() = "MlsInfoAdminUi(<redacted>)"
}
