// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeRowsEmitTypedIntentAndFollowSystemIsSelected() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent { RamfluxTheme { SettingsScreen(SettingsState(page = SettingsPage.APPEARANCE), { actions.add(it) }) } }
        compose.onNodeWithTag("theme:system").assertIsSelected()
        compose.onNodeWithTag("theme:dark").performClick()
        compose.runOnIdle { assertEquals(listOf(SettingsAction.Theme("dark")), actions) }
    }

    @Test fun failedDeviceReadDoesNotShowEmptySuccessAndChineseLabelsRemainLocalized() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme {
                    SettingsScreen(
                        SettingsState(
                            page = SettingsPage.DEVICES,
                            deviceProblem = uiText(org.ramflux.feature.settings.impl.R.string.settings_error)
                        ),
                        {}
                    )
                }
            }
        }
        compose.onNodeWithText("无法继续").assertExists()
        compose.onNodeWithText("账户组件未返回任何设备。").assertDoesNotExist()
    }

    @Test fun localeSwitchChangesThemeLabelsWithoutBusinessActions() {
        var locale by mutableStateOf("en")
        var actions = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                RamfluxTheme { SettingsScreen(SettingsState(page = SettingsPage.APPEARANCE), { actions++ }) }
            }
        }
        compose.onNodeWithText("Follow system").assertExists()
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("跟随系统").assertExists()
        compose.onNodeWithText("深色").assertExists()
        compose.runOnIdle { assertEquals(0, actions) }
    }
}
