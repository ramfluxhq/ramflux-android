// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.cards.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1400dp-mdpi")
class CardsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in listOf("unavailable", "checking", "error", "unverified", "closed"))
                for (dark in listOf(
                    false,
                    true
                )) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) add(arrayOf(kind, dark, locale, width))
        }
    }

    @Test fun screen() {
        val state = when (kind) {
            "checking" -> CardsState(checking = true, canCheck = true, unavailable = null)
            "error" -> CardsState(canCheck = true, unavailable = null, problem = uiText(R.string.cards_error_code, UiText.Number(8)))
            "unverified" -> CardsState(canCheck = true, unavailable = null, problem = uiText(R.string.cards_unverified))
            "closed" -> CardsState(closed = true, unavailable = null)
            else -> CardsState(canCheck = true)
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 600.dp))) {
                    RamfluxTheme(dark) { CardsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/cards-$kind-$dark-$locale-$width.png")
    }
}
