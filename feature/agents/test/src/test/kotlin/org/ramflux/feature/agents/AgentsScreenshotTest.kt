// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.ramflux.feature.agents.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1400dp-mdpi")
class AgentsScreenshotTest(private val kind: String, private val dark: Boolean, private val locale: String, private val width: Int) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}-{3}")
        fun cases(): List<Array<Any>> = buildList {
            for (kind in AgentCapability.entries.map { it.name } + listOf("loading", "error", "account", "invalid"))
                for (dark in listOf(
                    false,
                    true
                )) for (locale in listOf("en", "zh-CN", "en-XA")) for (width in listOf(400, 900)) add(arrayOf(kind, dark, locale, width))
        }
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> AgentsState(loading = true)
            "error" -> AgentsState(problem = uiText(R.string.agents_read_failure, UiText.Number(8)))
            "account" -> AgentsState(accountRequired = true)
            "invalid" -> AgentsState(
                mode = AgentCapability.CREATE,
                nameIssue = uiText(R.string.agents_name_required),
                goalIssue = uiText(R.string.agents_goal_required)
            )
            else -> AgentsState(mode = AgentCapability.valueOf(kind), unavailable = true)
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, if (width == 900) 1000.dp else 700.dp))
                ) {
                    RamfluxTheme(dark) { AgentsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/agents-$kind-$dark-$locale-$width.png")
    }
}
