// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.testing.screenshot

import android.text.TextUtils
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.util.Locale

/** A window the screen is laid out in. Widths come from the container, never from the screen. */
data class ScreenSize(val widthDp: Int, val heightDp: Int) {
    val label: String get() = "${widthDp}x$heightDp"
}

/** One screenshot configuration: window size, locale, theme and font scale. */
data class ScreenshotCase(val size: ScreenSize, val locale: String, val dark: Boolean, val fontScale: Float) {
    /** Stable file-name fragment, for example `393x852-ar-XB-dark-f2.0`. */
    val label: String get() = "${size.label}-$locale-${if (dark) "dark" else "light"}-f$fontScale"

    /** Only board-size captures at the default font scale are compared with design boards. */
    val boardComparable: Boolean get() = size == ScreenshotMatrix.BOARD && fontScale == 1f
}

/**
 * The shared screenshot parameter lists. Tests extend these lists instead of adding test classes;
 * no test duplicates a screen per locale. The locales are samples, not the shipped set: a
 * CJK sample, the long-text pseudo-locale and the right-to-left pseudo-locale.
 */
object ScreenshotMatrix {
    /** Design phone frame. */
    val BOARD = ScreenSize(393, 852)

    val sizes: List<ScreenSize> = listOf(
        ScreenSize(360, 800),
        BOARD,
        ScreenSize(700, 840),
        ScreenSize(1000, 760),
        ScreenSize(393, 400)
    )
    val locales: List<String> = listOf("en", "zh-CN", "en-XA", "ar-XB")
    val themes: List<Boolean> = listOf(false, true)
    val fontScales: List<Float> = listOf(1f, 2f)

    /** Robolectric window qualifier large enough for every size without a density change. */
    const val WINDOW_QUALIFIERS = "w1000dp-h852dp"

    /** Every combination of the four axes. */
    fun full(): List<ScreenshotCase> = sizes.flatMap { size ->
        locales.flatMap { locale ->
            themes.flatMap { dark -> fontScales.map { scale -> ScreenshotCase(size, locale, dark, scale) } }
        }
    }

    /** Every size, locale and theme at scale 1.0, plus scale 2.0 on the narrowest and the short window. */
    fun review(): List<ScreenshotCase> = full().filter { case ->
        case.fontScale == 1f || (!case.dark && (case.size == sizes.first() || case.size == sizes.last()))
    }

    /** Phone frame only: every locale and theme at scale 1.0. Used for secondary states. */
    fun compact(): List<ScreenshotCase> = full().filter { it.size == BOARD && it.fontScale == 1f }

    /** Cases with the given parameter rows prepended, for ParameterizedRobolectricTestRunner. */
    fun parameters(states: List<Any>, cases: (Any) -> List<ScreenshotCase>): List<Array<Any>> =
        states.flatMap { state -> cases(state).map { arrayOf(state, it) } }
}

/** Apply the case's locale, layout direction, font scale and window size to [content]. */
@OptIn(ExperimentalTestApi::class)
@Composable
fun ScreenshotFrame(case: ScreenshotCase, content: @Composable () -> Unit) {
    DeviceConfigurationOverride(
        DeviceConfigurationOverride.Locales(LocaleList(case.locale)) then
            DeviceConfigurationOverride.LayoutDirection(layoutDirectionOf(case.locale)) then
            DeviceConfigurationOverride.FontScale(case.fontScale) then
            DeviceConfigurationOverride.ForcedSize(DpSize(case.size.widthDp.dp, case.size.heightDp.dp))
    ) { content() }
}

/** Direction from the platform's locale data, so any added right-to-left locale works unchanged. */
fun layoutDirectionOf(tag: String): androidx.compose.ui.unit.LayoutDirection =
    if (TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == View.LAYOUT_DIRECTION_RTL) {
        androidx.compose.ui.unit.LayoutDirection.Rtl
    } else {
        androidx.compose.ui.unit.LayoutDirection.Ltr
    }

/** Texts that are ellipsized or overflow their layout bounds. Security and error text must never clip. */
fun SemanticsNodeInteractionsProvider.clippedTexts(): List<String> =
    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val clipped = layouts.any { layout ->
                val paragraph = layout.multiParagraph
                // A pixel of rounding between the paragraph and its layout box is not clipping.
                paragraph.didExceedMaxLines ||
                    paragraph.height - layout.size.height > 1f ||
                    (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            }
            if (clipped) node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text } ?: "<text>" else null
        }
