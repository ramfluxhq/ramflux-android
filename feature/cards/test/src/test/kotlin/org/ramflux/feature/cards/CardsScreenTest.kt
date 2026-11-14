// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.cards
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
class CardsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unavailableHasNoActionSurfaceAndCheckIsOnlyAnIntent() {
        val actions = mutableListOf<CardsAction>()
        compose.setContent { RamfluxTheme { CardsScreen(CardsState(canCheck = true), actions::add) } }
        compose.onNodeWithText("Cards unavailable").assertExists()
        compose.onNodeWithText("Check again").performClick()
        compose.runOnIdle { assertEquals(listOf(CardsAction.Refresh), actions) }
        compose.onNodeWithText("Confirm").assertDoesNotExist()
    }

    @Test fun actualReducerLocaleSwitchDoesNotRepeatAvailabilityCheck() {
        var locale by mutableStateOf("en")
        val repo = FakeCardHost()
        val vm = CardsViewModel(repo, FakeCardSession())
        vm.onAction(CardsAction.Visible(true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { CardsScreen(state, vm::onAction) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.canCheck && !vm.state.value.checking }
        val calls = repo.calls
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("卡片暂不可用").assertExists()
        compose.onNodeWithText("重新检查").assertExists()
        compose.runOnIdle { assertEquals(calls, repo.calls) }
        vm.onAction(CardsAction.Visible(false))
    }
}
