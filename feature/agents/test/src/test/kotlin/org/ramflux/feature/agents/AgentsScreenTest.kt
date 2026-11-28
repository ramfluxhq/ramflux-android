// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.agents
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.agents.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val nova = AgentRow("nova", "Nova", "Research partner", "orbit", uiText(R.string.agents_status_active), true)
    private val profile = AgentProfile(
        nova,
        "orbit",
        uiText(R.string.agents_role_named),
        uiText(R.string.agents_access_owner),
        uiText(R.string.agents_access_contacts)
    )

    @Test fun rowsEmitSelectionAndNoCreationIsOffered() {
        val actions = mutableListOf<AgentsAction>()
        compose.setContent {
            RamfluxTheme {
                AgentsScreen(
                    AgentsState(loaded = true, loadedCount = 1, rows = persistentListOf(nova)),
                    actions::add,
                    rooted = true
                )
            }
        }
        compose.onNodeWithTag("agent:nova").performClick()
        compose.onNodeWithText("Create Agent").assertDoesNotExist()
        compose.runOnIdle { assertEquals(AgentsAction.Select("nova"), actions.last()) }
    }

    @Test fun profileOffersMessageEditAndDeleteWithReadOnlyAccess() {
        val actions = mutableListOf<AgentsAction>()
        compose.setContent {
            RamfluxTheme { AgentsScreen(AgentsState(page = AgentsPage.PROFILE, loaded = true, selected = profile), actions::add) }
        }
        compose.onNodeWithText("Only you").assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("agent-message"))
        compose.onNodeWithTag("agent-message").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("agent-edit"))
        compose.onNodeWithTag("agent-edit").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("agent-delete"))
        compose.onNodeWithTag("agent-delete").performClick()
        compose.runOnIdle { assertEquals(listOf(AgentsAction.Message, AgentsAction.Edit, AgentsAction.Delete), actions) }
    }

    @Test fun chineseEmptyDirectoryPointsToTheDesktopAndFailureIsNotEmpty() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme { AgentsScreen(AgentsState(loaded = true), {}, rooted = true) }
            }
        }
        compose.onNodeWithText("还没有智能体").assertExists()
        compose.onNodeWithText("请在桌面端创建智能体").assertExists()
    }

    @Test fun failedFirstReadShowsRetryNotTheEmptyState() {
        val actions = mutableListOf<AgentsAction>()
        compose.setContent {
            RamfluxTheme {
                AgentsScreen(
                    AgentsState(problem = uiText(org.ramflux.core.designsystem.R.string.failure_generic)),
                    actions::add,
                    rooted = true
                )
            }
        }
        compose.onNodeWithTag("agents-empty").assertDoesNotExist()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(AgentsAction.Refresh, actions.last()) }
    }
}
