// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.groups
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
import org.ramflux.feature.groups.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowsAndHostConfirmationOnlyEmitTypedActions() {
        val actions = mutableListOf<GroupsAction>()
        val facts = GroupFacts(uiText(R.string.groups_create), persistentListOf(UiText.Data("account-A"), UiText.Data("group-A")))
        compose.setContent { RamfluxTheme { GroupsScreen(GroupsState(confirmation = facts), actions::add) } }
        compose.onNodeWithText("account-A").assertExists()
        compose.onNodeWithText("group-A").assertExists()
        compose.onNodeWithText("Deny").performClick()
        compose.runOnIdle { assertEquals(listOf(GroupsAction.Deny), actions) }
    }

    @Test fun chineseFailureIsNotAnEmptyGroupSuccess() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList("zh-CN"))) {
                RamfluxTheme { GroupsScreen(GroupsState(problem = uiText(R.string.groups_error_code, UiText.Number(8))), {}) }
            }
        }
        compose.onNodeWithText("无法继续").assertExists()
        compose.onNodeWithText("没有匹配的群组").assertDoesNotExist()
    }

    @Test fun actualViewModelLocaleChangeDoesNotRefetchAndPeerNamesAreNeverKeys() {
        var locale by mutableStateOf("en")
        val repo = FakeGroups().apply {
            listing = { org.ramflux.data.ClientResult.Ready(listOf(groupDetail().group.copy(name = "groups_title"))) }
        }
        val vm = GroupsViewModel(repo, FakeGroupContacts(), FakeGroupAccounts(), FakeGroupSession())
        vm.onAction(GroupsAction.Visible(true))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme { GroupsScreen(state, vm::onAction) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.loaded }
        val reads = repo.reads
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("创建群组").assertExists()
        compose.onNodeWithText("groups_title").assertExists()
        compose.runOnIdle { assertEquals(reads, repo.reads) }
        vm.onAction(GroupsAction.Visible(false))
    }
}
