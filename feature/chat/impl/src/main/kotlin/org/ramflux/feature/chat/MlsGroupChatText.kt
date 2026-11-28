// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.feature.chat.impl.R

/** A peer name inside a host sentence: "You", the neutral "Member" for a missing name, never the anchor. Isolated (S10). */
@Composable
private fun nameText(name: GroupName): String = when {
    name.me -> stringResource(R.string.group_timeline_you)
    name.display.isNullOrBlank() -> stringResource(R.string.chat_member_fallback)
    else -> Bidi.isolate(name.display.take(NAME_DISPLAY), UntrustedKind.Name)
}

/** The centred line of a service row. */
@Composable
internal fun groupLineText(line: GroupLine): String = when (line) {
    is GroupLine.Joined -> if (line.others > 0) {
        pluralStringResource(R.plurals.group_timeline_joined_many, line.others, nameText(line.first), line.others)
    } else {
        stringResource(R.string.group_timeline_joined, nameText(line.first))
    }
    is GroupLine.Left -> if (line.others > 0) {
        pluralStringResource(R.plurals.group_timeline_left_many, line.others, nameText(line.first), line.others)
    } else {
        stringResource(R.string.group_timeline_left, nameText(line.first))
    }
    is GroupLine.Removed ->
        line.actor?.let { stringResource(R.string.group_timeline_removed, nameText(it), nameText(line.target)) }
            ?: stringResource(R.string.group_timeline_removed_passive, nameText(line.target))
    is GroupLine.Banned ->
        line.actor?.let { stringResource(R.string.group_timeline_banned, nameText(it), nameText(line.target)) }
            ?: stringResource(R.string.group_timeline_banned_passive, nameText(line.target))
    is GroupLine.Role -> line.actor?.let {
        stringResource(
            if (line.admin) R.string.group_timeline_role_admin else R.string.group_timeline_role_member,
            nameText(it),
            nameText(line.target)
        )
    } ?: stringResource(R.string.group_timeline_updated)
    is GroupLine.Renamed -> line.actor?.let {
        stringResource(R.string.group_timeline_name, nameText(it), Bidi.isolate(line.name.take(NAME_DISPLAY), UntrustedKind.Name))
    } ?: stringResource(R.string.group_timeline_updated)
    is GroupLine.Approval -> line.actor?.let {
        stringResource(if (line.on) R.string.group_timeline_approval_on else R.string.group_timeline_approval_off, nameText(it))
    } ?: stringResource(R.string.group_timeline_updated)
    GroupLine.Void -> stringResource(R.string.group_timeline_void)
    GroupLine.Updated -> stringResource(R.string.group_timeline_updated)
    GroupLine.Unsupported -> stringResource(R.string.group_timeline_unsupported)
    GroupLine.Floor -> stringResource(R.string.group_timeline_floor)
}

/** The state line under a group row, or the text of a notice. */
@Composable
internal fun groupDeliveryText(delivery: GroupDelivery): String = when (delivery.reason) {
    GroupReason.CATCHING_UP -> stringResource(R.string.group_send_queued_catching_up)
    GroupReason.HOST_UNREACHABLE -> if (delivery.kind == GroupDeliveryKind.QUEUED) {
        stringResource(R.string.group_send_queued_host_unreachable)
    } else {
        stringResource(R.string.group_chat_error_host_unreachable)
    }
    GroupReason.FETCHING_TOKENS -> stringResource(R.string.group_send_queued_fetching_tokens)
    GroupReason.WAITING -> stringResource(R.string.group_send_queued_generic)
    GroupReason.DELAYED -> delivery.number?.let { stringResource(R.string.group_send_delayed, clock(it)) }
        ?: stringResource(R.string.group_send_queued_generic)
    GroupReason.RATE_LIMITED -> delivery.number?.let { stringResource(R.string.group_send_rate_limited, clock(it)) }
        ?: stringResource(R.string.group_send_rate_limited_no_reset)
    GroupReason.RATE_LIMITED_NO_RESET -> stringResource(R.string.group_send_rate_limited_no_reset)
    GroupReason.FIRST_DAY -> delivery.number?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt()?.let {
        pluralStringResource(R.plurals.group_send_rate_limited_first_day, it, it)
    } ?: stringResource(R.string.group_send_rate_limited_no_reset)
    GroupReason.TOO_LARGE -> delivery.number?.let {
        stringResource(R.string.group_chat_error_too_large, Formatter.formatFileSize(LocalContext.current, it))
    } ?: stringResource(R.string.group_chat_error_generic)
    GroupReason.NOT_MEMBER -> stringResource(R.string.group_chat_error_not_member)
    GroupReason.REJOIN_NEEDED -> stringResource(R.string.group_chat_error_rejoin_needed)
    GroupReason.PACED -> delivery.number?.let { stringResource(R.string.group_chat_error_paced, clock(it)) }
        ?: stringResource(R.string.group_chat_error_generic)
    GroupReason.DELETING -> stringResource(R.string.group_delete_deleting)
    GroupReason.UNAVAILABLE -> stringResource(R.string.group_chat_error_unavailable)
    GroupReason.MESSAGE_NOT_FOUND -> stringResource(R.string.group_chat_error_message_not_found)
    GroupReason.NOT_AUTHOR -> stringResource(R.string.group_chat_error_not_author)
    GroupReason.GENERIC -> stringResource(R.string.group_chat_error_generic)
}

@Composable
private fun clock(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    } catch (_: java.time.DateTimeException) {
        stringResource(R.string.group_send_queued_generic)
    }
}

private const val NAME_DISPLAY = 64
