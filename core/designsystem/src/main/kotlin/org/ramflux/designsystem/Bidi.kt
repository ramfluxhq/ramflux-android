// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

/** How a span of peer-supplied text is neutralised before display. */
enum class UntrustedKind {
    /** Display names and group names. */
    Name,

    /** File names: the real extension is shown separately and left-to-right. */
    FileName,

    /** Message text, previews and excerpts. */
    Excerpt
}

/**
 * Bidirectional text rules for peer data and codes.
 *
 * Visible behaviour:
 * - Name and FileName: every explicit embedding, override or isolate control (U+202A to U+202E,
 *   U+2066 to U+2069) is replaced by U+FFFD, so tampering stays visible and cannot reorder characters;
 *   the invisible marks U+200E, U+200F and U+061C are removed.
 * - Excerpt: explicit embedding, override and isolate controls are removed; the marks stay, because mixed
 *   Arabic or Hebrew and Latin text uses them legitimately and they cannot act outside the isolate.
 * - Inserted into a sentence, each untrusted span sits inside a first-strong isolate (FSI ... PDI), so it
 *   cannot reorder the surrounding host text. The real file extension follows the base name in its own
 *   left-to-right isolate, so a reversed or hidden extension cannot pass as another one.
 * - Codes (safety numbers, fingerprints, pairing codes, device ids, recovery words, URLs) sit inside a
 *   left-to-right isolate, all controls are removed and every decimal digit is shown as an ASCII digit.
 */
object Bidi {
    const val LRI: Char = '\u2066'
    const val RLI: Char = '\u2067'
    const val FSI: Char = '\u2068'
    const val PDI: Char = '\u2069'
    private const val REPLACEMENT: Char = '\uFFFD'
    private const val LRM: Char = '\u200E'
    private const val RLM: Char = '\u200F'
    private const val ALM: Char = '\u061C'
    private const val DOT: Char = '.'

    /** Explicit embeddings, overrides and isolates. */
    fun isExplicitControl(char: Char): Boolean = char in '\u202A'..'\u202E' || char in LRI..PDI

    fun isMark(char: Char): Boolean = char == LRM || char == RLM || char == ALM

    /** True when [value] carries any bidirectional formatting character; screens may warn about it. */
    fun hasControls(value: String): Boolean = value.any { isExplicitControl(it) || isMark(it) }

    fun sanitize(value: String, kind: UntrustedKind): String = buildString(value.length) {
        value.forEach { char ->
            when {
                isExplicitControl(char) -> if (kind != UntrustedKind.Excerpt) append(REPLACEMENT)
                isMark(char) -> if (kind == UntrustedKind.Excerpt) append(char)
                else -> append(char)
            }
        }
    }

    /** A sanitised span inside a first-strong isolate, for insertion into a host sentence. */
    fun isolate(value: String, kind: UntrustedKind = UntrustedKind.Name): String {
        if (kind == UntrustedKind.FileName) return wrap(FSI, fileName(value))
        return wrap(FSI, sanitize(value, kind))
    }

    /**
     * The sanitised base name followed by the real extension in its own left-to-right isolate. Shown as its
     * own paragraph, the base name decides the direction; inside a sentence use [isolate].
     */
    fun fileName(value: String): String {
        val parts = splitFileName(value)
        return if (parts.extension.isEmpty()) parts.base else parts.base + wrap(LRI, DOT.toString() + parts.extension)
    }

    /** Splits a sanitised file name at its last dot. The extension is what the system will act on. */
    fun splitFileName(value: String): FileNameParts {
        val clean = sanitize(value, UntrustedKind.FileName)
        val dot = clean.lastIndexOf(DOT)
        return if (dot <= 0 || dot == clean.lastIndex) {
            FileNameParts(clean, "")
        } else {
            FileNameParts(clean.substring(0, dot), clean.substring(dot + 1))
        }
    }

    /** Code text: controls removed and every decimal digit shown as an ASCII digit. */
    fun code(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            index += Character.charCount(point)
            when {
                point <= Char.MAX_VALUE.code && (isExplicitControl(point.toChar()) || isMark(point.toChar())) -> Unit
                Character.isDigit(point) && point > '9'.code -> append('0' + Character.digit(point, DECIMAL))
                else -> appendCodePoint(point)
            }
        }
    }

    /** A code inside a left-to-right isolate, for insertion into a host sentence. */
    fun ltr(value: String): String = wrap(LRI, code(value))

    data class FileNameParts(val base: String, val extension: String)

    private fun wrap(open: Char, body: String): String = buildString(body.length + 2) {
        append(open)
        append(body)
        append(PDI)
    }

    private const val DECIMAL = 10
}
