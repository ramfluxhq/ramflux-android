// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.android.R
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.kit.KitTab
import org.ramflux.designsystem.kit.KitTabBar
import org.ramflux.feature.conversations.ConversationItem
import org.ramflux.feature.conversations.ConversationsScreen
import org.ramflux.feature.conversations.ConversationsState
import org.ramflux.feature.groups.GroupRow
import org.ramflux.feature.groups.GroupsPage
import org.ramflux.feature.groups.GroupsScreen
import org.ramflux.feature.groups.GroupsState
import org.ramflux.feature.onboarding.OnboardingPage
import org.ramflux.feature.onboarding.OnboardingScreen
import org.ramflux.feature.onboarding.OnboardingState
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ShellBoardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 180_000)
    fun boardsMatchTheDesignFrame() {
        val frames = listOf("en" to "en", "zh-CN" to "zh").flatMap { (locale, suffix) ->
            listOf(
                BoardFrame("MOB-B01.light--$suffix", false, locale) { Tabs(0) { Messages(loaded = true) } },
                BoardFrame("MOB-B01L.light--$suffix", false, locale) { Tabs(0) { Messages(loading = true) } },
                BoardFrame("MOB-B01X.light--$suffix", false, locale) { Tabs(0) { Messages(problem = UiText.Data("Cannot connect")) } },
                BoardFrame("MOB-C01.light--$suffix", false, locale) { Tabs(1) { Groups() } },
                BoardFrame("MOB-G01.light--$suffix", false, locale) { Tabs(3) { MoreHub(DEFAULT_SHELL_ORDER, "", {}, {}, {}) } },
                BoardFrame("MOB-G03.light--$suffix", false, locale) { NavigationEditor(DEFAULT_SHELL_ORDER, "", {}, {}, {}) },
                BoardFrame("MOB-N01.light--$suffix", false, locale) {
                    OnboardingScreen(OnboardingState(page = OnboardingPage.CREATE, sessionReady = true), {})
                },
                BoardFrame("MOB-S01.light--$suffix", false, locale) { SplashScreen() },
                BoardFrame("MOB-S02.light--$suffix", false, locale) { GatewayScreen({}, {}) },
                BoardFrame("MOB-S18.light--$suffix", false, locale) { WelcomeScreen({}, {}, {}, {}) },
                BoardFrame("MOB-A01.light--$suffix", false, locale) { UnlockEntry("Ada", "https://node.example", {}, {}, {}) },
                BoardFrame("MOB-A02.light--$suffix", false, locale) { UnlockDetails("Ada", {}, {}) },
                BoardFrame("MOB-B01.dark--$suffix", true, locale) { Tabs(0) { Messages(loaded = true) } }
            )
        }
        val step = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val item = frames[step.intValue]
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(item.locale))) {
                RamfluxTheme(item.dark) {
                    Box(Modifier.fillMaxSize().testTag("board")) { item.body() }
                }
            }
        }
        frames.forEachIndexed { index, item ->
            step.intValue = index
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag("board").captureRoboImage("build/outputs/shell-roborazzi/${item.name}.png")
        }
    }

    @Composable
    private fun Tabs(selected: Int, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            KitTabBar(
                listOf(
                    KitTab("messages-square", stringResource(R.string.shell_tab_messages)),
                    KitTab("users-round", stringResource(R.string.shell_tab_groups)),
                    KitTab("bot", stringResource(R.string.shell_tab_agents), latin = true),
                    KitTab("ellipsis", stringResource(R.string.shell_tab_more))
                ),
                selected,
                {},
                tag = "board-tabs"
            )
        }
    }

    @Composable
    private fun Messages(loaded: Boolean = false, loading: Boolean = false, problem: UiText? = null) {
        ConversationsScreen(
            ConversationsState(
                accountLabel = "Ada",
                rows = if (loaded) {
                    persistentListOf(ConversationItem("c1", "Ada", UiText.Data("Hello"), "", false, UiText.Data("09:41")))
                } else {
                    persistentListOf()
                },
                loading = loading,
                loaded = loaded,
                problem = problem
            ),
            {},
            rooted = true
        )
    }

    @Composable
    private fun Groups() {
        GroupsScreen(
            GroupsState(
                page = GroupsPage.LIST,
                account = "Ada",
                loaded = true,
                groups = persistentListOf(GroupRow("g1", "Team", UiText.Data("3")))
            ),
            {},
            rooted = true
        )
    }
}

private class BoardFrame(val name: String, val dark: Boolean, val locale: String, val body: @Composable () -> Unit)
