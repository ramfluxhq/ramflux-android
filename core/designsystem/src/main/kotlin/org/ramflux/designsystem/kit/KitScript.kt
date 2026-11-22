// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

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

/** Every text drawn by the kit goes through here, so script rules hold in one place. */
@Composable
internal fun KitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null
) {
    val resolved = style.forScript(text).let { if (textAlign == null) it else it.copy(textAlign = textAlign) }
    Text(text, modifier, style = resolved, maxLines = maxLines, overflow = overflow)
}
