// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.math.BigInteger
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlinx.collections.immutable.*
import org.ramflux.core.designsystem.R

/** Host-owned text stays resource-backed. Peer display data is never treated as a translation key. */
@Immutable
sealed interface UiText {
    data object Empty : UiText
    data class Resource(@param:StringRes val id: Int, val args: ImmutableList<UiText> = persistentListOf()) : UiText
    data class Data(val value: String) : UiText
    data class Number(val value: Long) : UiText
    data class Unsigned(val value: ULong) : UiText
    data class DateTime(val epochSeconds: Long) : UiText

    /** A list-row time (D-8): the time today, Yesterday, the weekday within the past week, else the short date. */
    data class RelativeTime(val epochSeconds: Long) : UiText
    data class Quantity(@param:PluralsRes val id: Int, val value: ULong) : UiText

    /** Peer data inserted into a host sentence: sanitised and isolated, so it cannot reorder the sentence. */
    data class Untrusted(val value: String, val kind: UntrustedKind = UntrustedKind.Name) : UiText

    /** A code, fingerprint, phrase or URL inserted into a host sentence: always left-to-right, ASCII digits. */
    data class Code(val value: String) : UiText
}
fun uiText(@StringRes id: Int, vararg args: UiText): UiText = UiText.Resource(id, args.toList().toPersistentList())

@Composable
fun UiText.render(): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (this) {
        UiText.Empty -> ""
        is UiText.Resource -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.map { it.render() }.toTypedArray())
        is UiText.Data -> value
        is UiText.Untrusted -> Bidi.isolate(value, kind)
        is UiText.Code -> Bidi.ltr(value)
        is UiText.Unsigned -> NumberFormat.getIntegerInstance(locale).format(BigInteger(value.toString()))
        is UiText.Number -> NumberFormat.getIntegerInstance(locale).format(value)
        is UiText.Quantity -> pluralStringResource(
            id,
            pluralQuantity(value),
            NumberFormat.getIntegerInstance(locale).format(BigInteger(value.toString()))
        )
        is UiText.RelativeTime -> {
            val yesterday = stringResource(R.string.time_yesterday)
            val unavailable = stringResource(R.string.time_unavailable)
            try {
                val zone = ZoneId.systemDefault()
                val then = Instant.ofEpochSecond(epochSeconds).atZone(zone)
                val days = ChronoUnit.DAYS.between(then.toLocalDate(), LocalDate.now(zone))
                when {
                    days <= 0L -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(then)
                    days == 1L -> yesterday
                    days < DAYS_IN_WEEK -> DateTimeFormatter.ofPattern("EEE", locale).format(then)
                    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(then)
                }
            } catch (_: java.time.DateTimeException) {
                unavailable
            }
        }
        is UiText.DateTime -> try {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: java.time.DateTimeException) {
            stringResource(R.string.time_unavailable)
        }
    }
}

/**
 * The quantity handed to the platform plural rules, which select the CLDR category (zero, one, two, few,
 * many, other) for the active locale. A value above [Int.MAX_VALUE] keeps its last six digits, which
 * decide every CLDR integer rule (n % 10, n % 100, i % 1000000), and stays above the small-number cases.
 */
internal fun pluralQuantity(value: ULong): Int = if (value <= Int.MAX_VALUE.toULong()) {
    value.toInt()
} else {
    (value % PLURAL_MODULUS + PLURAL_MODULUS).toInt()
}

private const val PLURAL_MODULUS = 1_000_000uL
private const val DAYS_IN_WEEK = 7L

@Composable
fun localizedUnsigned(value: String): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    .format(BigInteger(value))
