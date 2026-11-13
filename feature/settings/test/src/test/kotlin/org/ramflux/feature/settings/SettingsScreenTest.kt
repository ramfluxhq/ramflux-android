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
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noAccountDevicesExplainsBothLocalesAndEmitsAccountActionWithoutPrivateReads() {
        var locale by mutableStateOf("en")
        val accounts = FakeSettingsAccounts().apply { result = ClientResult.Ready(AccountCatalog(emptyList(), null)) }
        val devices = FakeDeviceDirectory()
        val status = FakeAccountStatus()
        val vm = SettingsViewModel(accounts, devices, status, FakeSettingsAppearance(), FakeSettingsSession(), FakeSettingsLanguage())
        vm.onAction(SettingsAction.Page(SettingsPage.DEVICES))
        val actions = mutableListOf<SettingsAction>()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { SettingsScreen(state, actions::add) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.accountRequired }
        compose.onNodeWithText("Open an unlocked account to read its status and devices.").assertExists()
        compose.onNodeWithText("No devices were returned by the account library.").assertDoesNotExist()
        compose.onNodeWithText("Open account").performClick()
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("请打开已解锁的账户以查看状态和设备。").assertExists()
        compose.onNodeWithText("账户组件未返回任何设备。").assertDoesNotExist()
        compose.onNodeWithText("打开账户").performClick()
        compose.runOnIdle {
            assertEquals(listOf(SettingsAction.OpenAccount, SettingsAction.OpenAccount), actions)
            assertEquals(0, devices.reads)
            assertEquals(0, status.reads)
        }
    }

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
