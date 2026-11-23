// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.ramflux.designsystem.kit

import android.icu.util.ULocale
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import org.ramflux.core.designsystem.R

/**
 * Font families by script. The bundled faces cover Latin only: Hanken Grotesk where the design specifies it
 * for Latin text and JetBrains Mono for codes. Every other script uses the platform sans, which picks the
 * right face per script (Noto Sans CJK, Noto Naskh Arabic, Noto Sans Devanagari and so on) with that face's
 * own line metrics.
 */
internal object RfFonts {
    val ui: FontFamily = FontFamily.SansSerif
    val latin: FontFamily = family(R.font.hanken_grotesk)
    val mono: FontFamily = family(R.font.jetbrains_mono)

    /** The theme family for a UI locale: the Latin face only when the locale's likely script is Latin. */
    fun forLocale(locale: Locale): FontFamily = if (isLatinScript(locale)) latin else ui

    fun isLatinScript(locale: Locale): Boolean {
        val script = ULocale.addLikelySubtags(ULocale.forLocale(locale)).script
        return script.equals(LATIN_SCRIPT, ignoreCase = true)
    }

    private const val LATIN_SCRIPT = "latn"

    private fun family(res: Int): FontFamily = FontFamily(
        face(res, FontWeight.Normal),
        face(res, FontWeight.Medium),
        face(res, FontWeight.SemiBold),
        face(res, FontWeight.Bold)
    )

    private fun face(res: Int, weight: FontWeight) = Font(
        res,
        weight = weight,
        variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
    )
}
