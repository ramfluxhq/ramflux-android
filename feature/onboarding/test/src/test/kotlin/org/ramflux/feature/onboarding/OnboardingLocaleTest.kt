// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.onboarding
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingLocaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseLocaleRendersHostResourcesAndSwitchDoesNotEmitBusinessActions() {
        var locale by mutableStateOf("en")
        var actions = 0
        val state = OnboardingState(page = OnboardingPage.ADDRESS, sessionReady = true, address = "node.example")
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                RamfluxTheme { OnboardingScreen(state, { actions++ }) }
            }
        }
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("验证节点").assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
