// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.ramflux.data.cards.CardEvent
import org.ramflux.data.cards.CardNode
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitButton
import org.ramflux.designsystem.kit.KitButtonKind
import org.ramflux.designsystem.kit.KitCheckbox
import org.ramflux.designsystem.kit.KitMeta
import org.ramflux.designsystem.kit.KitText
import org.ramflux.designsystem.kit.KitTextField
import org.ramflux.designsystem.kit.kitStyle
import org.ramflux.designsystem.kit.shapeOf

/** The text of a Button: its first Text child. */
private fun buttonLabel(node: CardNode, ctx: CardContext): String =
    node.children.firstNotNullOfOrNull { child -> ctx.tree.nodes[child]?.attrs?.str("text") }.orEmpty()

/** Button (spec 4.9): activation is one event; the submit is the host's next call after `pending_submit`. */
@Composable
internal fun CardButton(node: CardNode, ctx: CardContext) {
    val kind = when (node.attrs.str("variant")) {
        "primary" -> KitButtonKind.Primary
        "borderless" -> KitButtonKind.Text
        else -> KitButtonKind.Secondary
    }
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    val busy = ctx.submitting && ctx.pressed.value == node.key
    val label = if (busy) stringResource(R.string.card_submitting) else buttonLabel(node, ctx)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        KitButton(
            label = label,
            onClick = {
                ctx.pressed.value = node.key
                ctx.event(node.key, CardEvent.Activate)
            },
            kind = kind,
            enabled = enabled,
            loading = busy
        )
        node.attrs.firstError()?.let { KitMeta(it) }
    }
}

/** TextField (spec 4.10): the shown value is kept while typing; each change is one `input` event. */
@Composable
internal fun CardTextField(node: CardNode, ctx: CardContext) {
    val key = node.key
    val treeValue = node.attrs.str("value").orEmpty()
    val staleHere = ctx.input.staleKey == key
    val staleSeq = if (staleHere) ctx.input.staleSeq else 0L
    var draft by remember(key, treeValue, staleSeq) { mutableStateOf(treeValue) }
    var focused by remember(key) { mutableStateOf(false) }
    var touched by remember(key) { mutableStateOf(false) }
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    val number = node.attrs.str("variant") == "number"
    val error = when {
        staleHere && staleSeq > 0 -> stringResource(R.string.card_stale_value)
        else -> node.attrs.firstError()
    }
    KitTextField(
        value = draft,
        onValue = { value ->
            if (enabled) {
                draft = value
                ctx.event(key, CardEvent.Input(fieldValue(value, number)))
            }
        },
        label = trusted(node.attrs.str("label").orEmpty()),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.hasFocus) touched = true
            focused = state.hasFocus
        },
        error = error?.takeIf { touched || (staleHere && staleSeq > 0) },
        enabled = enabled
    )
}

private fun fieldValue(value: String, number: Boolean): JsonPrimitive {
    if (!number) return JsonPrimitive(value)
    value.toLongOrNull()?.let { return JsonPrimitive(it) }
    value.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    return JsonPrimitive(value)
}

/** CheckBox (spec 4.11): the whole row toggles; the state is sent as a boolean `input`. */
@Composable
internal fun CardCheckBox(node: CardNode, ctx: CardContext) {
    val key = node.key
    val treeChecked = node.attrs.bool("value") == true
    var checked by remember(key, treeChecked) { mutableStateOf(treeChecked) }
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    Column(Modifier.fillMaxWidth()) {
        KitCheckbox(
            checked = checked,
            onChecked = { value ->
                if (enabled) {
                    checked = value
                    ctx.event(key, CardEvent.Input(JsonPrimitive(value)))
                }
            },
            enabled = enabled,
            label = trusted(node.attrs.str("label").orEmpty())
        )
        node.attrs.firstError()?.let { KitMeta(it) }
    }
}

/** ChoicePicker (spec 4.12): checkbox rows, radio rows (mutually exclusive) or chips. The value is sent as the same JSON shape. */
@Composable
internal fun CardChoicePicker(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val key = node.key
    val options = node.attrs.arr("options")?.mapNotNull { it as? JsonObject }.orEmpty()
    val exclusive = node.attrs.str("variant") == "mutuallyExclusive"
    val treeValues = selectedValues(node.attrs["value"])
    var selected by remember(key, treeValues) { mutableStateOf(treeValues) }
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    fun choose(value: String) {
        if (!enabled) return
        val next = when {
            exclusive -> setOf(value)
            value in selected -> selected - value
            else -> selected + value
        }
        selected = next
        val ordered = options.mapNotNull { it.str("value") }.filter { it in next }
        val payload: JsonElement = if (exclusive) {
            JsonPrimitive(
                ordered.firstOrNull().orEmpty()
            )
        } else {
            JsonArray(ordered.map { JsonPrimitive(it) })
        }
        ctx.event(key, CardEvent.Input(payload))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        node.attrs.str(
            "label"
        )?.let { KitText(trusted(it), style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.secondary, 1.448f)) }
        if (node.attrs.str("displayStyle") == "chips") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val value = option.str("value").orEmpty()
                    FilterChip(
                        selected = value in selected,
                        onClick = { choose(value) },
                        enabled = enabled,
                        label = { Text(trusted(option.str("label").orEmpty())) }
                    )
                }
            }
        } else {
            options.forEach { option ->
                val value = option.str("value").orEmpty()
                val on = value in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clickable(enabled = enabled, role = if (exclusive) Role.RadioButton else Role.Checkbox) { choose(value) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (exclusive) {
                        RadioButton(
                            selected = on,
                            onClick = null,
                            enabled = enabled
                        )
                    } else {
                        Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
                    }
                    Spacer(Modifier.width(12.dp))
                    KitText(
                        trusted(option.str("label").orEmpty()),
                        style = kitStyle(theme.size_body, FontWeight.Normal, theme.text, 1.448f)
                    )
                }
            }
        }
        node.attrs.firstError()?.let { KitMeta(it) }
    }
}

/** The selected values of a ChoicePicker value: an array (multiple) or one string (mutually exclusive). */
private fun selectedValues(value: JsonElement?): Set<String> = when (value) {
    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.toSet()
    is JsonPrimitive -> value.takeIf { it.isString }?.content?.let { setOf(it) }.orEmpty()
    else -> emptySet()
}

/**
 * Slider (spec 4.13): the thumb moves locally; the final value is sent once on release. The runtime takes a whole number
 * inside the node's bounds, so the released value is rounded and kept between the first and the last whole number.
 */
@Composable
internal fun CardSlider(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val key = node.key
    val min = node.attrs.num("min") ?: 0.0
    val max = (node.attrs.num("max") ?: 100.0).coerceAtLeast(min + 1e-6)
    val treeValue = (node.attrs.num("value") ?: min).toFloat()
    var draft by remember(key, treeValue) { mutableFloatStateOf(treeValue) }
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KitText(
                trusted(node.attrs.str("label").orEmpty()),
                Modifier.weight(1f),
                style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.secondary, 1.448f)
            )
            KitMeta(formatNumber(draft.toDouble(), 1.0, ctx.format, null))
        }
        Slider(
            value = draft,
            onValueChange = { if (enabled) draft = it },
            onValueChangeFinished = {
                val whole = draft.roundToLong().coerceIn(ceil(min).toLong(), floor(max).toLong())
                if (enabled) ctx.event(key, CardEvent.Input(JsonPrimitive(whole)))
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled
        )
        node.attrs.firstError()?.let { KitMeta(it) }
    }
}

/**
 * DateTimeInput (spec 4.14): the Material date and time pickers. Values travel as `yyyy-MM-dd` (date), `HH:mm` (time) or
 * `yyyy-MM-dd'T'HH:mm` (both); the runtime validates the value and its min and max.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CardDateTime(node: CardNode, ctx: CardContext) {
    val theme = LocalRFTheme.current
    val key = node.key
    val useDate = node.attrs.bool("enableDate") == true
    val useTime = node.attrs.bool("enableTime") == true
    val raw = node.attrs.str("value").orEmpty()
    val locale = ctx.format
    val date = if (useDate) runCatching { LocalDate.parse(raw.take(10)) }.getOrNull() else null
    val time = if (useTime) runCatching { LocalTime.parse(raw.substringAfter('T', raw).take(5)) }.getOrNull() else null
    val enabled = ctx.live && node.attrs.bool("disabled") != true
    var pickingDate by remember(key) { mutableStateOf(false) }
    var pickingTime by remember(key) { mutableStateOf(false) }
    var pendingDay by remember(key) { mutableStateOf<LocalDate?>(null) }
    val shown = formatDateTime(date, time, locale, useDate, useTime)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KitText(
            trusted(node.attrs.str("label").orEmpty()),
            style = kitStyle(theme.size_secondary, FontWeight.SemiBold, theme.secondary, 1.448f)
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(1.dp, theme.input_border, shapeOf(theme.radius_input))
                .background(theme.input, shapeOf(theme.radius_input))
                .clickable(enabled = enabled && (useDate || useTime), role = Role.Button) {
                    pickingDate = useDate
                    pickingTime = !useDate && useTime
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            KitText(
                shown.ifEmpty { stringResource(R.string.card_date_placeholder) },
                style = kitStyle(theme.size_body, FontWeight.Normal, if (shown.isEmpty()) theme.control else theme.text, 1.448f)
            )
        }
        node.attrs.firstError()?.let { KitMeta(it) }
    }
    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (date ?: LocalDate.now()).toEpochDay() * DAY_MILLIS)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val picked = state.selectedDateMillis?.let { LocalDate.ofEpochDay(it / DAY_MILLIS) }
                    pickingDate = false
                    if (picked != null) {
                        if (useTime) {
                            pickingTime = true
                        } else {
                            ctx.event(key, CardEvent.Input(JsonPrimitive(picked.format(DateTimeFormatter.ISO_LOCAL_DATE))))
                        }
                        pendingDay = picked
                    }
                }) { Text(stringResource(R.string.card_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.card_cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
    if (pickingTime) {
        val start = time ?: LocalTime.NOON
        val state = rememberTimePickerState(initialHour = start.hour, initialMinute = start.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    pickingTime = false
                    val day = pendingDay ?: date ?: LocalDate.now()
                    val value = if (useDate) {
                        "${day.format(
                            DateTimeFormatter.ISO_LOCAL_DATE
                        )}T${String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute)}"
                    } else {
                        String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute)
                    }
                    ctx.event(key, CardEvent.Input(JsonPrimitive(value)))
                    pendingDay = null
                }) { Text(stringResource(R.string.card_ok)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pickingTime = false
                    pendingDay = null
                }) { Text(stringResource(R.string.card_cancel)) }
            },
            text = { TimePicker(state = state) }
        )
    }
}

private const val DAY_MILLIS = 86_400_000L

private fun formatDateTime(date: LocalDate?, time: LocalTime?, locale: Locale, useDate: Boolean, useTime: Boolean): String {
    val parts = ArrayList<String>(2)
    if (useDate && date != null) parts.add(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)))
    if (useTime && time != null) parts.add(time.format(DateTimeFormatter.ofPattern("HH:mm", locale)))
    return parts.joinToString(" ")
}
