// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.ramflux.designsystem.Bidi
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.UntrustedKind

/**
 * Codes users compare: safety numbers, fingerprints, pairing codes, the six-digit code, device ids, recovery
 * words and URLs. Always left-to-right in the bundled mono face with ASCII digits taken from the raw value,
 * never from a number formatter. Groups keep their order in every layout direction and wrap left to right;
 * each group is its own accessibility node, and digit groups are read digit by digit.
 *
 * Groups come from whitespace in [value], or from fixed runs of [groupSize] characters when it is above
 * zero. [numbered] prefixes each group with its 1-based position, for recovery phrases.
 */
@Composable
fun LtrCode(
    value: String,
    modifier: Modifier = Modifier,
    groupSize: Int = 0,
    numbered: Boolean = false,
    size: Float? = null,
    weight: FontWeight = FontWeight.Normal,
    tracking: Float = 0f,
    line: Float = RfMetrics.lineBody,
    centered: Boolean = false,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val textSize = size ?: theme.size_body
    val groups = ltrCodeGroups(value, groupSize, numbered)
    val style = kitStyle(textSize, weight, theme.text, line, RfFonts.mono, tracking)
        .copy(textDirection = TextDirection.Ltr)
    // One mono space plus the tracking, so grouped codes keep the spacing of the joined string.
    val gap = with(LocalDensity.current) { (textSize * MONO_ADVANCE + tracking).sp.toDp() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        FlowRow(
            modifier.kitTag(tag),
            horizontalArrangement = Arrangement.spacedBy(gap, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            groups.forEach { group ->
                val spoken = if (group.all { it.isDigit() }) group.toList().joinToString(SPACE.toString()) else null
                KitText(group, Modifier.semantics { if (spoken != null) contentDescription = spoken }, style = style)
            }
        }
    }
}

/**
 * Secret words that must never become a String, such as the one-time recovery phrase: the [LtrCode] rules
 * (left to right in every locale, the mono face, numbered words in reading order, text size from the font
 * scale) applied to borrowed character arrays. [draw] lends word `index`'s characters only while a frame
 * is drawn; nothing is copied, laid out as a String or exposed to accessibility, so the owner's
 * show-once and erasure guarantees hold. Numbers are ASCII. The columns follow the available width;
 * a word longer than a column is narrowed to fit instead of overlapping its neighbour.
 */
@Composable
fun LtrSecretWords(
    count: Int,
    draw: (Int, (CharArray) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    val theme = LocalRFTheme.current
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(RfFonts.mono).value as? Typeface ?: Typeface.MONOSPACE
    val color = theme.text.toArgb()
    val textPx = with(density) { theme.size_body.sp.toPx() }
    val paint = remember(typeface, textPx, color) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = textPx
            this.color = color
        }
    }
    val numberStyle = kitStyle(theme.size_body, FontWeight.Normal, theme.secondary, RfMetrics.lineBody, RfFonts.mono)
    val gap = dp(RfMetrics.secretGap)
    val line = with(density) { (theme.size_body * RfMetrics.lineBody).sp.toDp() }
    val numberWidth = with(density) { (textPx * MONO_ADVANCE * (count.toString().length + 1)).toDp() }
    val wordWidth = with(density) { (textPx * MONO_ADVANCE * SECRET_WORD_CHARS).toDp() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxWidth().kitTag(tag)) {
            val columns = (maxWidth / (numberWidth + wordWidth + gap)).toInt().coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in 0 until (count + columns - 1) / columns) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (column in 0 until columns) {
                            val index = row * columns + column
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                if (index < count) {
                                    KitText((index + 1).toString(), Modifier.width(numberWidth), style = numberStyle)
                                    SecretWord(index, draw, paint, Modifier.weight(1f).height(line).kitTag(tag?.let { "$it-$index" }))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretWord(index: Int, draw: (Int, (CharArray) -> Unit) -> Unit, paint: Paint, modifier: Modifier) {
    Canvas(modifier) {
        val metrics = paint.fontMetrics
        val baseline = size.height / 2f - (metrics.ascent + metrics.descent) / 2f
        draw(index) { chars ->
            val width = paint.measureText(chars, 0, chars.size)
            val canvas = drawContext.canvas.nativeCanvas
            val save = canvas.save()
            if (width > size.width && width > 0f) canvas.scale(size.width / width, 1f)
            canvas.drawText(chars, 0, chars.size, 0f, baseline, paint)
            canvas.restoreToCount(save)
        }
    }
}

/** The display groups of a code, with ASCII digits and no bidirectional controls. */
fun ltrCodeGroups(value: String, groupSize: Int = 0, numbered: Boolean = false): List<String> {
    val clean = Bidi.code(value)
    val groups = if (groupSize > 0) {
        clean.filterNot { it.isWhitespace() }.chunked(groupSize)
    } else {
        words(clean)
    }
    return if (numbered) groups.mapIndexed { index, group -> (index + 1).toString() + SPACE + group } else groups
}

private fun words(value: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    value.forEach { char ->
        if (char.isWhitespace()) {
            if (current.isNotEmpty()) result += current.toString()
            current.clear()
        } else {
            current.append(char)
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

/**
 * Peer-supplied text: display names, group names, file names and message excerpts. The text is neutralised
 * by [Bidi.sanitize] and laid out as its own paragraph with first-strong direction, so it can neither
 * reorder neighbouring host text nor disguise a file extension. [style] defaults to the kit body text.
 */
@Composable
fun UntrustedText(
    value: String,
    modifier: Modifier = Modifier,
    kind: UntrustedKind = UntrustedKind.Name,
    style: TextStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    val theme = LocalRFTheme.current
    val base = style ?: kitStyle(theme.size_mobile_body, FontWeight.Normal, theme.text, RfMetrics.lineBody)
    KitText(
        untrustedDisplay(value, kind),
        modifier,
        style = base.copy(textDirection = TextDirection.Content),
        maxLines = maxLines,
        overflow = overflow
    )
}

/** What [UntrustedText] shows for [value]. */
fun untrustedDisplay(value: String, kind: UntrustedKind): String =
    if (kind == UntrustedKind.FileName) Bidi.fileName(value) else Bidi.sanitize(value, kind)

private const val MONO_ADVANCE = 0.6f

// Room for the longest common recovery word (eight letters) plus a space.
private const val SECRET_WORD_CHARS = 9
private const val SPACE = ' '
