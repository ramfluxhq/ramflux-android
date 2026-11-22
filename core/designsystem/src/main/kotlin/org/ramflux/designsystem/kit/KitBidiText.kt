// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
private const val SPACE = ' '
