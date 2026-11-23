// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat

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
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Media storage (media design B30, M3) in the review matrix: state x theme x locale, plus large text. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class MediaStorageScreenshotTest(
    private val kind: String,
    private val dark: Boolean,
    private val locale: String,
    private val fontScale: Float
) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-font{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in listOf("loading", "empty", "rows", "confirm")) {
                for (locale in listOf("en", "zh-CN", "en-XA", "ar-XB")) {
                    add(arrayOf(kind, false, locale, 1f))
                    add(arrayOf(kind, true, locale, 1f))
                }
            }
            add(arrayOf("rows", false, "en", 2f))
        }
    }

    @Test fun screen() {
        val rows = persistentListOf(
            StorageRow("c1", "Project", 48_200_000),
            StorageRow("c2", "林悦", 3_100_000),
            StorageRow("c3", "", 920_000)
        )
        val state = when (kind) {
            "loading" -> MediaStorageState()
            "empty" -> MediaStorageState(loading = false)
            "confirm" -> MediaStorageState(loading = false, total = 52_220_000, outbox = 1_200_000, rows = rows, confirm = "c1")
            else -> MediaStorageState(loading = false, total = 52_220_000, outbox = 1_200_000, rows = rows)
        }
        val direction = if (locale == "ar-XB") LayoutDirection.Rtl else LayoutDirection.Ltr
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.LayoutDirection(direction)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(393.dp, 852.dp))) {
                            RamfluxTheme(dark) { MediaStorageScreen(state, {}, {}) }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/storage-$kind-$dark-$locale-font$fontScale.png")
    }
}
