// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.uiText
import org.ramflux.feature.app_shell.impl.R
import org.ramflux.testing.screenshot.ScreenshotCase
import org.ramflux.testing.screenshot.ScreenshotFrame
import org.ramflux.testing.screenshot.ScreenshotMatrix
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** One parameterised matrix over the shared screenshot cases; secondary states at the phone frame. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "${ScreenshotMatrix.WINDOW_QUALIFIERS}-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class AppShellScreenshotTest(private val kind: String, private val case: ScreenshotCase) {
    @get:Rule val compose = createComposeRule()

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = ScreenshotMatrix.parameters(listOf("ready", "loading", "error", "unavailable")) { kind ->
            if (kind == "ready") ScreenshotMatrix.review() else ScreenshotMatrix.compact()
        }
    }

    @Test fun shell() {
        val state = when (kind) {
            "loading" -> AppShellState()
            "error" -> AppShellState(
                loading = false,
                canRetry = true,
                error = ShellNotice(uiText(R.string.ui_unable_to_continue), uiText(R.string.ui_the_setting_or_private_data_could_not_be))
            )
            "unavailable" -> AppShellState(
                ready = true,
                loading = false,
                notice = ShellNotice(
                    uiText(R.string.ui_not_available_yet),
                    uiText(R.string.ui_this_feature_is_not_included_in_this_build),
                    ShellNoticeKind.UNAVAILABLE
                )
            )
            else -> AppShellState(status = uiText(R.string.ui_client_ready), ready = true, loading = false)
        }
        compose.setContent { ScreenshotFrame(case) { RamfluxTheme(case.dark) { AppShellScreen(state, {}) } } }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/shell-$kind-${case.label}.png")
    }
}
