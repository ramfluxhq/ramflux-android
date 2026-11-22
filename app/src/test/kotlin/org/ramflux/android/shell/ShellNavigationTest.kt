// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.feature.agents.api.AgentsRoute
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.appshell.api.NoticesRoute
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.contacts.api.ContactsRoute
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.groups.api.GroupsRoute
import org.ramflux.navigation.PaneRole
import org.ramflux.navigation.TopLevelNavigator
import org.ramflux.navigation.pane
import org.ramflux.navigation.rememberTopLevelNavigator
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The tabbed shell with stand-in tab content: tab stacks, panes, bar placement and restoration. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h900dp-mdpi")
class ShellNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var navigator: TopLevelNavigator

    private fun EntryProviderScope<NavKey>.fakeEntries(shell: ShellNavigation) {
        entry<ConversationsRoute>(metadata = pane(PaneRole.LIST)) {
            Column {
                BasicText("conversation list")
                TextButton({ shell.openDetail(ChatRoute("a", "c1")) }) { BasicText("open c1") }
                TextButton({ shell.openDetail(ChatRoute("a", "c2")) }) { BasicText("open c2") }
            }
        }
        entry<ChatRoute>(metadata = pane(PaneRole.DETAIL)) { BasicText("chat " + it.conversationId) }
        entry<GroupsRoute>(metadata = pane(PaneRole.LIST)) {
            var taps by rememberSaveable { mutableIntStateOf(0) }
            TextButton({ taps++ }) { BasicText("groups taps $taps") }
        }
        entry<AgentsRoute> { BasicText("agents") }
        entry<MoreRoute> {
            Column {
                BasicText("more hub")
                TextButton({ shell.navigate(NoticesRoute) }) { BasicText("open notices") }
                TextButton({ shell.navigate(ContactsRoute) }) { BasicText("open contacts") }
            }
        }
        entry<NoticesRoute> { BasicText("notices page") }
        entry<ContactsRoute> { BasicText("contacts page") }
    }

    @Composable
    private fun Shell(width: Int) {
        navigator = rememberTopLevelNavigator(SHELL_TAB_ROOTS)
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 800.dp))) {
            RamfluxTheme {
                RamfluxShell(navigator, SHELL_ROUTE_POLICY, {}, insets = WindowInsets(0, 0, 0, 0)) { fakeEntries(it) }
            }
        }
    }

    @Test fun compactPushesChatOverTheListAndHidesTheBar() {
        compose.setContent { Shell(393) }
        compose.onNodeWithText("open c1").performClick()
        compose.onNodeWithText("chat c1").assertExists()
        compose.onNodeWithText("conversation list").assertDoesNotExist()
        compose.onNodeWithTag("shell-bottom-bar").assertDoesNotExist()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithText("conversation list").assertExists()
        compose.onNodeWithTag("shell-bottom-bar").assertExists()
    }

    @Test fun expandedShowsListAndDetailSideBySideAndBackClearsTheSelection() {
        compose.setContent { Shell(1000) }
        compose.onNodeWithTag("pane-list").assertExists()
        compose.onNodeWithTag("shell-detail-empty").assertExists()
        compose.onNodeWithText("open c1").performClick()
        compose.onNodeWithText("chat c1").assertExists()
        compose.onNodeWithText("conversation list").assertExists()
        compose.onNodeWithTag("shell-bottom-bar").assertExists()
        compose.onNodeWithText("open c2").performClick()
        compose.onNodeWithText("chat c2").assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithText("chat c2").assertDoesNotExist()
        compose.onNodeWithTag("shell-detail-empty").assertExists()
        compose.onNodeWithText("conversation list").assertExists()
    }

    @Test fun unfoldingAndFoldingKeepTheSelectedConversation() {
        var width by mutableStateOf(393)
        compose.setContent { Shell(width) }
        compose.onNodeWithText("open c1").performClick()
        compose.onNodeWithText("conversation list").assertDoesNotExist()
        width = 1000
        compose.onNodeWithText("chat c1").assertExists()
        compose.onNodeWithText("conversation list").assertExists()
        width = 393
        compose.onNodeWithText("chat c1").assertExists()
        compose.onNodeWithText("conversation list").assertDoesNotExist()
    }

    @Test fun switchingTabsKeepsEachTabsHistoryAndSavedState() {
        compose.setContent { Shell(393) }
        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("groups taps 0").performClick()
        compose.onNodeWithText("groups taps 1").assertExists()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("open notices").performClick()
        compose.onNodeWithText("notices page").assertExists()
        compose.runOnIdle { navigator.select(1) }
        compose.onNodeWithText("groups taps 1").assertExists()
        compose.runOnIdle { navigator.select(3) }
        compose.onNodeWithText("notices page").assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithText("more hub").assertExists()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.onNodeWithText("conversation list").assertExists()
    }

    @Test fun stacksAndSelectedTabSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Shell(1000) }
        compose.onNodeWithText("open c2").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("open contacts").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("contacts page").assertExists()
        compose.runOnIdle { assertEquals(3, navigator.selected) }
        compose.runOnIdle { navigator.select(0) }
        compose.onNodeWithText("chat c2").assertExists()
    }

    @Test fun lockedSessionDropsAccountRoutesFromEveryTab() {
        compose.setContent { Shell(1000) }
        compose.onNodeWithText("open c1").performClick()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("open contacts").performClick()
        compose.runOnIdle { navigator.dropAccountScoped(SHELL_ROUTE_POLICY::isAccountScoped) }
        compose.onNodeWithText("more hub").assertExists()
        compose.runOnIdle { assertEquals(listOf<NavKey>(ConversationsRoute), navigator.stacks[0].toList()) }
    }
}
