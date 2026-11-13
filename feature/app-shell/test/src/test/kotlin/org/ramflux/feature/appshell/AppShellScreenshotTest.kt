// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.uiText
import org.ramflux.feature.app_shell.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppShellScreenshotTest(
    private val width: Int,
    private val height: Int,
    private val dark: Boolean,
    private val font: Float,
    private val localeTag: String
) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}x{1}-dark{2}-font{3}-locale{4}")
        fun sizes(): List<Array<Any>> = baseCases().flatMap { row ->
            listOf("en", "zh-CN", "en-XA").map { tag -> Array<Any>(row.size + 1) { index -> if (index < row.size) row[index] else tag } }
        }
        private fun baseCases(): List<Array<Any>> = buildList {
            for (w in listOf(
                400,
                610,
                900
            )) for (h in listOf(400, 500, 1000)) for (dark in listOf(false, true)) add(arrayOf(w, h, dark, 1f))
            add(arrayOf(400, 500, false, 1.5f))
            add(arrayOf(400, 500, true, 1.5f))
        }
    }

    @Test fun shell() {
        var state by mutableStateOf(AppShellState(status = uiText(R.string.ui_client_ready), ready = true, loading = false))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(localeTag))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, height.dp))) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, font)
                    ) { RamfluxTheme(dark) { AppShellScreen(state, {}) } }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell-$width-$height-$dark-$font-$localeTag.png")
        if (width == 400 && height == 500 && !dark && font == 1f) {
            for ((name, next) in listOf(
                "loading" to AppShellState(),
                "error" to AppShellState(
                    loading = false, canRetry = true,
                    error = ShellNotice(
                        uiText(R.string.ui_unable_to_continue),
                        uiText(R.string.ui_the_setting_or_private_data_could_not_be)
                    )
                ),
                "unavailable" to AppShellState(
                    ready = true, loading = false,
                    notice = ShellNotice(
                        uiText(R.string.ui_not_available_yet), uiText(R.string.ui_this_feature_is_not_included_in_this_build),
                        ShellNoticeKind.UNAVAILABLE
                    )
                )
            )) {
                compose.runOnIdle { state = next }
                compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell-$name-$localeTag.png")
            }
        }
    }
}
