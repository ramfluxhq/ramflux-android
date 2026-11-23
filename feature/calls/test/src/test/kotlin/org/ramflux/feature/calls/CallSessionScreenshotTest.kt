// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.LayoutDirection
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** The call screen's stages (beta flow 12, up to the media-engine boundary) in the review matrix. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class CallSessionScreenshotTest(
    private val stage: CallStage,
    private val dark: Boolean,
    private val locale: String,
    private val fontScale: Float
) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-font{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (stage in CallStage.entries) {
                for (locale in listOf("en", "zh-CN", "en-XA", "ar-XB")) add(arrayOf(stage, false, locale, 1f))
                add(arrayOf(stage, true, "en", 1f))
            }
            add(arrayOf(CallStage.INCOMING, false, "en", 2f))
            add(arrayOf(CallStage.IN_CALL, false, "en", 2f))
        }
    }

    @Test fun screen() {
        val state = CallSessionState(
            stage = stage,
            peer = "林悦",
            incoming = stage == CallStage.INCOMING,
            // Calls carry no media yet (request R28): the in-call stage says so.
            mediaUnavailable = stage == CallStage.IN_CALL
        )
        val direction = if (locale == "ar-XB") LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.LayoutDirection(direction)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(393.dp, 852.dp))) {
                            RamfluxTheme(dark) { CallSessionScreen(state, {}) }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/call-${stage.name.lowercase()}-$dark-$locale-font$fontScale.png")
    }
}
