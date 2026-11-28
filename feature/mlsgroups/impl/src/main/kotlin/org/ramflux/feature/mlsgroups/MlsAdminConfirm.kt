// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.feature.mlsgroups.impl.R

/** What one admin confirmation says. The strings are resources; the person and group names are peer data and are isolated. */
internal data class MlsAdminConfirmSpec(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val confirm: Int,
    /** Remove, ban and approval off are losses: the confirm button is the destructive one. */
    val destructive: Boolean,
    /** The member or requester the action is about; null shows "this member", never an anchor. */
    val display: String?,
    val groupName: String?,
    /** The member is an agent: the AI marker and the agent note show (A12). */
    val ai: Boolean = false,
    /** The argument of the body text (the expiry time of a declined request). */
    val bodyArg: String? = null,
    /** A second paragraph under the body (the agent hint of the approval-off confirmation). */
    @param:StringRes val bodyExtra: Int? = null
)

/**
 * The one confirmation of every admin action (approve, decline, role, remove, ban, approval off): the title names the person
 * and the group, the body says what it does, and only the confirm button dispatches (A1, A11). The back key, the scrim and
 * "Cancel" dismiss it.
 */
@Composable
internal fun BoxScope.MlsAdminConfirm(spec: MlsAdminConfirmSpec, tag: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val person = spec.display?.let { Bidi.isolate(it.take(NAME_DISPLAY), UntrustedKind.Name) } ?: stringResource(R.string.group_member_this)
    val group = spec.groupName?.let { Bidi.isolate(it.take(NAME_DISPLAY), UntrustedKind.Name) }
        ?: stringResource(R.string.group_join_the_group)
    val first = stringResource(spec.body, spec.bodyArg.orEmpty())
    val extra = spec.bodyExtra?.let { stringResource(it) }
    val body = if (extra == null) first else buildString { append(first).append('\n').append('\n').append(extra) }
    ConfirmPanel(
        stringResource(spec.title, person, group),
        body,
        stringResource(spec.confirm),
        stringResource(R.string.group_invite_cancel),
        onConfirm,
        onCancel,
        tag,
        destructive = spec.destructive,
        ai = spec.ai,
        note = stringResource(R.string.group_member_agent_note),
        confirmTag = "mls-confirm-primary",
        cancelTag = "mls-confirm-cancel"
    )
}

internal fun approveSpec(display: String?, ai: Boolean, group: String?) = MlsAdminConfirmSpec(
    R.string.group_requests_approve_confirm_title,
    R.string.group_requests_approve_confirm_body,
    R.string.group_requests_approve_confirm_confirm,
    false,
    display,
    group,
    ai
)

internal fun declineSpec(display: String?, ai: Boolean, group: String?, expires: String?) = MlsAdminConfirmSpec(
    R.string.group_requests_decline_confirm_title,
    R.string.group_requests_decline_confirm_body,
    R.string.group_requests_decline_confirm_confirm,
    false,
    display,
    group,
    ai,
    expires
)

internal fun approvalOffSpec() = MlsAdminConfirmSpec(
    R.string.group_settings_approval_off_confirm_title, R.string.group_settings_approval_off_confirm_body,
    R.string.group_settings_approval_off_confirm_confirm, true, null, null, false, null, R.string.group_hint_agent_mobile
)

internal fun memberSpec(kind: MlsMemberActionKind, display: String?, ai: Boolean, group: String?) = when (kind) {
    MlsMemberActionKind.MAKE_ADMIN -> MlsAdminConfirmSpec(
        R.string.group_member_admin_confirm_title,
        R.string.group_member_admin_confirm_body,
        R.string.group_member_admin_confirm_confirm,
        false,
        display,
        group,
        ai
    )
    MlsMemberActionKind.MAKE_MEMBER -> MlsAdminConfirmSpec(
        R.string.group_member_demote_confirm_title,
        R.string.group_member_demote_confirm_body,
        R.string.group_member_demote_confirm_confirm,
        false,
        display,
        group,
        ai
    )
    MlsMemberActionKind.REMOVE -> MlsAdminConfirmSpec(
        R.string.group_member_remove_confirm_title,
        R.string.group_member_remove_confirm_body,
        R.string.group_member_remove_confirm_confirm,
        true,
        display,
        group,
        ai
    )
    MlsMemberActionKind.BAN -> MlsAdminConfirmSpec(
        R.string.group_member_ban_confirm_title,
        R.string.group_member_ban_confirm_body,
        R.string.group_member_ban_confirm_confirm,
        true,
        display,
        group,
        ai
    )
}

private const val NAME_DISPLAY = 64
