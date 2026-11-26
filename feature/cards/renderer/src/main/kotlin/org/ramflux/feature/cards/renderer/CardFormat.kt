// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards.renderer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.ramflux.designsystem.UntrustedKind
import org.ramflux.designsystem.kit.untrustedDisplay

/** Typed reads of one tree node's attributes. Missing or mistyped values read as null; nothing here throws. */
internal fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

internal fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arr(name: String): JsonArray? = this[name] as? JsonArray

/** The first runtime-owned validation message of a node, if any (`errors` may hold strings or objects with a message). */
internal fun JsonObject.firstError(): String? {
    val first = arr("errors")?.firstOrNull() ?: return null
    return when (first) {
        is JsonPrimitive -> first.takeIf { it.isString }?.content
        is JsonObject -> first.str("message") ?: first.str("text")
        else -> null
    }?.takeIf { it.isNotEmpty() }
}

/** Tree text is untrusted: it reaches the screen only through [untrustedDisplay], never as markup or links. */
internal fun trusted(value: String, kind: UntrustedKind = UntrustedKind.Excerpt): String = untrustedDisplay(value, kind)

/** The Locale the tree asks numbers, dates and plurals to use (CLDR tag from the runtime). */
internal fun formatLocale(tag: String?): Locale = tag?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: Locale.ENGLISH

/** Simple Markdown of Text nodes (spec 4.1): paragraphs, `- ` lists, **bold**, *italic* and `code`. No headings, images or links. */
internal fun markdownBlocks(source: String): List<Pair<Boolean, AnnotatedString>> {
    val out = ArrayList<Pair<Boolean, AnnotatedString>>()
    for (raw in source.split("\n")) {
        val line = raw.trimEnd()
        if (line.isBlank()) continue
        val bullet = line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") }
        val text = if (bullet) line.trimStart().substring(2) else line
        out.add(bullet to inline(trusted(text)))
    }
    return out
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            text[i] == '*' && i + 1 < text.length && text[i + 1] != '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(text[i])
                    i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}

/** Renders a number value with the tree's format locale: `value / scale` with an optional unit. */
internal fun formatNumber(value: Double, scale: Double, locale: Locale, unit: String?): String {
    val divisor = if (scale == 0.0) 1.0 else scale
    val formatted = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 3 }.format(value / divisor)
    return if (unit.isNullOrEmpty()) formatted else "$formatted $unit"
}
