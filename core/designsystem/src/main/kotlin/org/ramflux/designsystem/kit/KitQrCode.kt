// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.floor

/**
 * A QR code drawn module by module from a matrix the caller lends for the duration of one draw.
 *
 * [lend] calls its block with a reader `dark(x, y)` over [modules] x [modules] modules and may erase the
 * matrix afterwards; the Kit copies nothing and keeps no bitmap, so a secret payload (a pairing
 * invitation) lives only in the caller's erasable matrix. Modules are drawn dark on light in both themes,
 * as the QR specification reads them, with the four-module quiet zone, snapped to whole pixels and never
 * mirrored in right-to-left layouts, centred and at most 320 dp wide. Nothing is drawn while [modules] is
 * zero.
 */
@Composable
fun KitQrCode(
    modules: Int,
    lend: (block: (dark: (Int, Int) -> Boolean) -> Unit) -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tag: String? = null
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrModules(modules, lend, contentDescription, tag)
    }
}

@Composable
private fun QrModules(
    modules: Int,
    lend: (block: (dark: (Int, Int) -> Boolean) -> Unit) -> Unit,
    contentDescription: String,
    tag: String?
) {
    Spacer(
        Modifier
            .widthIn(max = dp(QR_MAX_SIDE))
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics { this.contentDescription = contentDescription }
            .kitTag(tag)
            .drawBehind {
                if (modules <= 0) return@drawBehind
                val cells = modules + 2 * QR_QUIET_MODULES
                val cell = floor(size.minDimension / cells)
                if (cell < 1f) return@drawBehind
                val side = cell * cells
                val origin = Offset(floor((size.width - side) / 2f), floor((size.height - side) / 2f))
                drawRect(Color.White, origin, Size(side, side))
                val module = Size(cell, cell)
                val start = origin + Offset(cell * QR_QUIET_MODULES, cell * QR_QUIET_MODULES)
                lend { dark ->
                    for (y in 0 until modules) {
                        for (x in 0 until modules) {
                            if (dark(x, y)) drawRect(Color.Black, start + Offset(cell * x, cell * y), module)
                        }
                    }
                }
            }
    )
}

/** Light modules around the symbol, the minimum of ISO/IEC 18004. */
private const val QR_QUIET_MODULES = 4

/** The symbol's largest side in dp: a phone camera reads it from about 20 cm; wider windows centre it. */
private const val QR_MAX_SIDE = 320f
