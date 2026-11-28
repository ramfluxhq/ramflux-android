// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.mlsgroups

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.ramflux.data.MlsFailure
import org.ramflux.data.MlsGroupRefusal
import org.ramflux.data.MlsGroupRejoinReason
import org.ramflux.data.MlsGroupWarning
import org.ramflux.feature.mlsgroups.impl.R

private const val SECS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L

/** The typed text of a failed or unavailable call. An unknown code is the generic text, never the code itself. */
@Composable
internal fun failureText(failure: MlsFailure): String = when {
    failure.unavailable -> stringResource(R.string.group_error_unavailable)
    failure.code == "not_member" -> stringResource(R.string.group_error_not_member)
    failure.code == "not_admin" -> stringResource(R.string.group_error_not_admin)
    failure.code == "not_owner" -> stringResource(R.string.group_error_not_owner)
    failure.code == "void_not_head" -> stringResource(R.string.group_error_void_not_head)
    failure.code == "owner_protected" -> stringResource(R.string.group_error_owner_protected)
    failure.code == "tier_unavailable" -> stringResource(R.string.group_error_tier_unavailable)
    failure.code == "group_full" -> stringResource(R.string.group_error_group_full)
    failure.code == "host_unreachable" -> stringResource(R.string.group_error_host_unreachable)
    failure.code == "unknown_invite" -> stringResource(R.string.group_error_unknown_invite)
    failure.code == "request_not_found" -> stringResource(R.string.group_error_request_not_found)
    failure.code == "rate_limited" -> stringResource(R.string.group_error_rate_limited)
    failure.code == "paced" && failure.detail != null -> stringResource(R.string.group_error_paced, timeText(failure.detail ?: 0L))
    failure.code == "paced" -> stringResource(R.string.group_error_paced_later)
    else -> stringResource(R.string.group_error_generic)
}

/** The typed text of a join refusal; an unknown reason is the generic refusal. */
@Composable
internal fun refusalText(result: MlsJoinResultUi.Refused): String = when (result.reason) {
    MlsGroupRefusal.LINK_INVALID -> stringResource(R.string.group_join_refused_link_invalid)
    MlsGroupRefusal.INVITE_INVALID -> stringResource(R.string.group_join_refused_invite_invalid)
    MlsGroupRefusal.INVITE_EXPIRED ->
        result.expiredAt?.let { stringResource(R.string.group_join_refused_invite_expired, timeText(it)) }
            ?: stringResource(R.string.group_join_refused_generic)
    MlsGroupRefusal.GROUP_FULL -> stringResource(R.string.group_join_refused_group_full)
    MlsGroupRefusal.QUEUE_FULL -> stringResource(R.string.group_join_refused_queue_full)
    MlsGroupRefusal.REQUEST_EXPIRED -> stringResource(R.string.group_join_refused_request_expired)
    MlsGroupRefusal.ALREADY_MEMBER -> stringResource(R.string.group_join_refused_already_member)
    MlsGroupRefusal.RATE_LIMITED ->
        result.retryAfterSecs?.let { stringResource(R.string.group_join_refused_rate_limited, durationText(it)) }
            ?: stringResource(R.string.group_join_refused_generic)
    MlsGroupRefusal.UNKNOWN -> stringResource(R.string.group_join_refused_generic)
}

/** A unix-millisecond time as a short localized date and time. */
@Composable
internal fun timeText(epochMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return try {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))
    } catch (_: java.time.DateTimeException) {
        stringResource(R.string.group_info_loading)
    }
}

/** The time of a list row: the clock time today, else the date. */
@Composable
internal fun activityText(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val locale = LocalConfiguration.current.locales[0]
    return try {
        val zone = ZoneId.systemDefault()
        val moment = Instant.ofEpochMilli(epochMillis).atZone(zone)
        if (moment.toLocalDate() == LocalDate.now(zone)) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(moment)
        } else {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(moment)
        }
    } catch (_: java.time.DateTimeException) {
        ""
    }
}

/** A wait: minutes under one hour, otherwise hours, in the locale's own unit names. */
@Composable
internal fun durationText(seconds: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val minutes = maxOf(1L, (seconds + SECS_PER_MINUTE - 1) / SECS_PER_MINUTE)
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE)
    return if (minutes < MINUTES_PER_HOUR) {
        format.format(Measure(minutes, MeasureUnit.MINUTE))
    } else {
        format.format(Measure((minutes + MINUTES_PER_HOUR - 1) / MINUTES_PER_HOUR, MeasureUnit.HOUR))
    }
}

/** The link cut in the middle for display; the full link is what is copied, shared and sent. */
internal fun middleTruncated(link: String, keep: Int = LINK_KEEP): String =
    if (link.length <= keep * 2 + 1) link else link.take(keep) + '…' + link.takeLast(keep)

private const val LINK_KEEP = 16

/** Parts joined by a middle dot with a space on each side. */
internal fun dotted(parts: List<String>): String = parts.joinToString(separator = DOT)

private val DOT = charArrayOf(' ', '\u00B7', ' ').concatToString()

/** The text of one warning row of group info; it repeats the banner text of the chat. An unknown code is the generic text. */
@Composable
internal fun warningText(warning: MlsGroupWarning): String = when (warning) {
    is MlsGroupWarning.ForkAlarm -> stringResource(R.string.group_warning_fork_alarm)
    is MlsGroupWarning.RejoinNeeded -> stringResource(
        when (warning.reason) {
            MlsGroupRejoinReason.PRUNED -> R.string.group_warning_rejoin_needed_pruned
            MlsGroupRejoinReason.GATE_REJECTED -> R.string.group_warning_rejoin_needed_gate_rejected
            MlsGroupRejoinReason.APPLY_FAILED -> R.string.group_warning_rejoin_needed_apply_failed
            MlsGroupRejoinReason.WELCOME_EXPIRED -> R.string.group_warning_rejoin_needed_welcome_expired
            MlsGroupRejoinReason.STATE_LOST -> R.string.group_warning_rejoin_needed_state_lost
            MlsGroupRejoinReason.UNKNOWN -> R.string.group_warning_rejoin_needed_generic
        }
    )
    is MlsGroupWarning.Undecryptable -> {
        val count = warning.count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        pluralStringResource(R.plurals.group_warning_undecryptable, count, count)
    }
    MlsGroupWarning.AdmissionNeedsOnline -> stringResource(R.string.group_warning_admission_needs_online)
    // One text for every kind of `host_unusual`.
    is MlsGroupWarning.HostUnusual -> stringResource(R.string.group_warning_host_unusual)
    is MlsGroupWarning.HeadCommitRejected -> stringResource(R.string.group_warning_head_commit_rejected)
    is MlsGroupWarning.HostIpVisible, is MlsGroupWarning.Unknown -> stringResource(R.string.group_warning_generic)
}
