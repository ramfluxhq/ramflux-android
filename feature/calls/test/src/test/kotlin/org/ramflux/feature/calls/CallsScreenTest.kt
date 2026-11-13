// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.calls
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.calls.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
fun callItem() = CallItem(
    "call", "Actual peer", uiText(R.string.calls_incoming), uiText(R.string.calls_incoming_ringing), UiText.DateTime(1700000000),
    ULong.MAX_VALUE, UiText.Unsigned(ULong.MAX_VALUE), null, null, null, true, true, true, false
)

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixedIncomingActionsCarryExactSequenceAndAnswerOnlyEmitsUnavailableIntent() {
        val row = callItem()
        val actions = mutableListOf<CallsAction>()
        compose.setContent {
            RamfluxTheme {
                CallsScreen(
                    CallsState(selected = row, rows = persistentListOf(row), fresh = true),
                    actions::add
                )
            }
        }
        compose.onNodeWithText("Actual peer").assertExists()
        compose.onNodeWithText("Decline call").assertIsDisplayed().performClick()
        compose.onNodeWithText("Answer").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(CallsAction.End("call", ULong.MAX_VALUE, true), CallsAction.Audio), actions) }
    }

    @Test fun chineseReadFailureDoesNotClaimEmptyHistory() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme { CallsScreen(CallsState(problem = uiText(R.string.calls_error_code, UiText.Number(8))), {}) }
            }
        }
        compose.onNodeWithText("无法读取通话").assertExists()
        compose.onNodeWithText("暂无通话记录").assertDoesNotExist()
    }

    @Test fun actualViewModelLocaleSwitchReformatsHostLabelsWithoutAnotherRead() {
        var locale by mutableStateOf("en")
        val repo = FakeCalls()
        val vm = CallsViewModel(repo, FakeCallAccounts(), FakeCallSession())
        vm.onAction(CallsAction.Visible(true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { CallsScreen(state, vm::onAction) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.loaded }
        val reads = repo.reads
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("通话记录").assertExists()
        compose.onNodeWithText("peer").assertExists()
        compose.runOnIdle { assertEquals(reads, repo.reads) }
        vm.onAction(CallsAction.Visible(false))
    }
}
