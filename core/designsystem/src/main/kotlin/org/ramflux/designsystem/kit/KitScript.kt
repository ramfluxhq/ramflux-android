// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** What the kit needs to know about the scripts in one string. */
internal enum class KitScript {
    /** Latin letters, digits and punctuation only: the bundled Latin faces and tracking apply. */
    Latin,

    /** Scripts whose glyphs stay inside the platform line box, such as Han, Kana, Hangul and Cyrillic. */
    Other,

    /** Scripts with marks above or below the line, such as Arabic, Hebrew, Devanagari and Thai. */
    Tall;

    companion object {
        private val tall = setOf(
            Character.UnicodeScript.ARABIC, Character.UnicodeScript.HEBREW, Character.UnicodeScript.SYRIAC,
            Character.UnicodeScript.THAANA, Character.UnicodeScript.NKO, Character.UnicodeScript.DEVANAGARI,
            Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI, Character.UnicodeScript.GUJARATI,
            Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
            Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.SINHALA,
            Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.TIBETAN,
            Character.UnicodeScript.MYANMAR, Character.UnicodeScript.KHMER
        )
        private val neutral = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED)

        fun of(text: String): KitScript {
            var result = Latin
            var index = 0
            while (index < text.length) {
                val point = text.codePointAt(index)
                index += Character.charCount(point)
                val script = Character.UnicodeScript.of(point)
                if (script in tall) return Tall
                if (script !in neutral) result = Other
            }
            return result
        }
    }
}

private val noTracking = 0f.sp

/**
 * Adapts a kit style to the scripts in [text]. The bundled Latin face and positive tracking apply only to
 * Latin text; other scripts use the platform font, which selects the right face per script. Scripts with
 * marks above or below the line keep the full line box and a taller line, so the marks are not clipped.
 */
internal fun TextStyle.forScript(text: String): TextStyle {
    val script = KitScript.of(text)
    if (script == KitScript.Latin) return this
    var style = this
    if (fontFamily == RfFonts.latin) style = style.copy(fontFamily = RfFonts.ui)
    if (letterSpacing != noTracking && letterSpacing.isSpecified()) style = style.copy(letterSpacing = noTracking)
    if (script == KitScript.Tall) {
        val size = fontSize.value
        val line = if (lineHeight.isSpecified()) {
            maxOf(
                lineHeight.value,
                size * RfMetrics.lineTallScript
            )
        } else {
            size * RfMetrics.lineTallScript
        }
        style = style.copy(
            lineHeight = line.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        )
    }
    return style
}

private fun androidx.compose.ui.unit.TextUnit.isSpecified(): Boolean = this != androidx.compose.ui.unit.TextUnit.Unspecified

/** Share of each line a taller platform face may overhang the design line box (CJK faces need about 15%). */
private const val DESIGN_LINE_SLACK = 0.25f

/**
 * Every text drawn by the kit goes through here, so script rules hold in one place.
 *
 * [designLine] makes the text report the design line box (line count times the style line height) when the
 * platform font is taller than it, as the CJK faces are; the glyphs stay centred and are not clipped. Rows
 * and headers use it so their design frames hold at the default font scale. Above the default font scale the
 * text always reports its full height: the design frames no longer apply there, and absorbing the overhang
 * would let stacked texts overlap (a row's label and value) or a title overhang its header.
 */
@Composable
internal fun KitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
    designLine: Boolean = false
) {
    val resolved = style.forScript(text).let { if (textAlign == null) it else it.copy(textAlign = textAlign) }
    if (!designLine || !resolved.lineHeight.isSpecified() || LocalDensity.current.fontScale > 1f) {
        Text(text, modifier, style = resolved, maxLines = maxLines, overflow = overflow)
        return
    }
    var lines = 1
    Text(
        text,
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val box = (resolved.lineHeight.toPx() * lines).roundToInt()
            // Only what a taller platform face adds (a fraction of each line) is absorbed; text that really
            // needs more room (large font scales, tall marks) reports its full height and never overlaps.
            val slack = (resolved.lineHeight.toPx() * DESIGN_LINE_SLACK * lines).roundToInt()
            val height = minOf(placeable.height, maxOf(box, placeable.height - slack, constraints.minHeight))
            layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
        },
        style = resolved,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { lines = it.lineCount }
    )
}
