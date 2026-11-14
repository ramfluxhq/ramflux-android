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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
    data class Quantity(@param:PluralsRes val id: Int, val value: ULong) : UiText
}
fun uiText(@StringRes id: Int, vararg args: UiText): UiText = UiText.Resource(id, args.toList().toPersistentList())

@Composable
fun UiText.render(): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (this) {
        UiText.Empty -> ""
        is UiText.Resource -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.map { it.render() }.toTypedArray())
        is UiText.Data -> value
        is UiText.Unsigned -> NumberFormat.getIntegerInstance(locale).format(BigInteger(value.toString()))
        is UiText.Number -> NumberFormat.getIntegerInstance(locale).format(value)
        is UiText.Quantity -> pluralStringResource(
            id,
            if (value == 1uL) 1 else 2,
            NumberFormat.getIntegerInstance(locale).format(BigInteger(value.toString()))
        )
        is UiText.DateTime -> try {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochSecond(epochSeconds))
        } catch (_: java.time.DateTimeException) {
            stringResource(R.string.time_unavailable)
        }
    }
}

@Composable
fun localizedUnsigned(value: String): String = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    .format(BigInteger(value))
