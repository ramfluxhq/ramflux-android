// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.ramflux.designsystem.kit

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import org.ramflux.core.designsystem.R

internal object RfFonts {
    val ui: FontFamily = FontFamily.SansSerif
    val latin: FontFamily = family(R.font.hanken_grotesk)
    val mono: FontFamily = family(R.font.jetbrains_mono)

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
