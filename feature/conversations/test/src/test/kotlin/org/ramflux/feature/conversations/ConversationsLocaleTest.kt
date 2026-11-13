// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
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
class ConversationsLocaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseLocaleRendersHostResourcesAndSwitchDoesNotEmitBusinessActions() {
        var locale by mutableStateOf("en")
        var actions = 0
        val state = ConversationsState(accountLabel = "Work account", loaded = true)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                RamfluxTheme { ConversationsScreen(state, { actions++ }) }
            }
        }
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("暂无会话").assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
