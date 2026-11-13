// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
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
import org.ramflux.feature.agents.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun creationIsOnlyAnIntentAndDraftFieldsHaveLocalizedAdjacentErrors() {
        val actions = mutableListOf<AgentsAction>()
        compose.setContent {
            RamfluxTheme {
                AgentsScreen(
                    AgentsState(
                        mode = AgentCapability.CREATE,
                        nameIssue = uiText(R.string.agents_name_required),
                        goalIssue = uiText(R.string.agents_goal_required)
                    ),
                    actions::add
                )
            }
        }
        compose.onNodeWithText("Enter a name.").performScrollTo().assertExists()
        compose.onNodeWithText("Enter a responsibility.").performScrollTo().assertExists()
        compose.onAllNodesWithText("Create Agent").onLast().performClick()
        compose.runOnIdle { assertEquals(listOf(AgentsAction.Create), actions) }
    }

    @Test fun chineseUnavailableDoesNotClaimAnEmptyOrCreatedAgent() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme { AgentsScreen(AgentsState(unavailable = true), {}) }
            }
        }
        compose.onNodeWithText("Agent 配置暂不可用").assertExists()
        compose.onNodeWithText("此 SDK 不支持创建或配置 Agent。尚未创建或更改任何 Agent。").assertExists()
    }

    @Test fun actualViewModelPreservesLiteralDraftAcrossLocaleChangeWithoutAnotherRepositoryRead() {
        var locale by mutableStateOf("en")
        val repo = FakeAgentConfiguration()
        val vm = AgentsViewModel(repo, FakeAgentAccounts(), FakeAgentSession())
        vm.onAction(AgentsAction.Visible(true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { AgentsScreen(state, vm::onAction) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.unavailable }
        compose.runOnIdle { vm.onAction(AgentsAction.Mode(AgentCapability.CREATE)) }
        compose.waitUntil(5_000) { vm.state.value.mode == AgentCapability.CREATE && !vm.state.value.loading }
        compose.runOnIdle {
            vm.onAction(AgentsAction.Name("agents_title"))
            vm.onAction(AgentsAction.Goal("Literal responsibility"))
        }
        val reads = repo.modes.size
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("名称").performScrollTo().assertExists()
        compose.onNodeWithText("agents_title").assertExists()
        compose.runOnIdle { assertEquals(reads, repo.modes.size) }
        vm.onAction(AgentsAction.Visible(false))
    }
}
