// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import kotlinx.collections.immutable.persistentListOf
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.feature.agents.AgentsScreen
import org.ramflux.feature.agents.AgentsState
import org.ramflux.feature.agents.api.AgentsRoute
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.appshell.api.NavigationEditorRoute
import org.ramflux.feature.chat.ChatScreen
import org.ramflux.feature.chat.ChatState
import org.ramflux.feature.chat.MessageItem
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.conversations.ConversationsScreen
import org.ramflux.feature.conversations.ConversationsState
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.groups.GroupRow
import org.ramflux.feature.groups.GroupsPage
import org.ramflux.feature.groups.GroupsScreen
import org.ramflux.feature.groups.GroupsState
import org.ramflux.feature.groups.api.GroupsRoute
import org.ramflux.feature.groups.impl.R as GroupsR
import org.ramflux.feature.onboarding.OnboardingPage
import org.ramflux.feature.onboarding.OnboardingScreen
import org.ramflux.feature.onboarding.OnboardingState
import org.ramflux.feature.settings.SettingsScreen
import org.ramflux.feature.settings.SettingsState
import org.ramflux.feature.settings.api.SettingsRoute
import org.ramflux.navigation.PaneRole
import org.ramflux.navigation.TopLevelNavigator
import org.ramflux.navigation.pane
import org.ramflux.testing.fixtures.ConversationBoards
import org.ramflux.testing.screenshot.ScreenshotCase
import org.ramflux.testing.screenshot.ScreenshotFrame
import org.ramflux.testing.screenshot.ScreenshotMatrix
import org.ramflux.testing.screenshot.clippedTexts
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Board captures and the shell screenshot matrix.
 *
 * [boards] renders every board of `shell-previews/manifest.json` at the design frame (393x852 dp at
 * xhdpi, 2 px per dp, the export scale) with the board's content, the board's system bar insets
 * (59 dp status bar, 34 dp home indicator) and the real shell around tab screens, in every
 * reference locale and both themes. compare-board-captures.py compares them with the references.
 *
 * [matrix] renders representative shell states over the shared screenshot matrix for review and
 * asserts that no security text clips.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ShellBoardScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test(timeout = 900_000)
    @Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
    fun boards() {
        val manifest = JSONObject(requireNotNull(javaClass.classLoader).getResource("shell-previews/manifest.json").readText())
        val boards = manifest.getJSONArray("boards")
        val captured = (0 until boards.length()).map { boards.getJSONObject(it) }.filter { it.optBoolean("capture", true) }
        val missing = captured.map { it.getString("key") }.distinct().filterNot { it in BOARDS }
        assertTrue("manifest boards without a renderer: $missing", missing.isEmpty())
        val locales = (0 until boards.length()).map { boards.getJSONObject(it).getString("locale") }.distinct()
        val frames = captured.map { it.getString("key") }.distinct().flatMap { board ->
            locales.flatMap { locale -> listOf(false, true).map { dark -> Triple(board, locale, dark) } }
        }
        val frame = ScreenshotMatrix.BOARD
        val step = mutableIntStateOf(0)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val (board, locale, dark) = frames[step.intValue]
            key(step.intValue) {
                ScreenshotFrame(ScreenshotCase(frame, locale, dark, 1f)) {
                    RamfluxTheme(dark) {
                        Box(Modifier.fillMaxSize().testTag("board")) { requireNotNull(BOARDS[board]).invoke() }
                    }
                }
            }
        }
        frames.forEachIndexed { index, (board, locale, dark) ->
            step.intValue = index
            Snapshot.sendApplyNotifications()
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            val theme = if (dark) "dark" else "light"
            compose.onNodeWithTag("board").captureRoboImage("build/outputs/shell-roborazzi/$board.$theme--$locale.png")
        }
    }

    @Test(timeout = 900_000)
    @Config(sdk = [35], qualifiers = "${ScreenshotMatrix.WINDOW_QUALIFIERS}-mdpi")
    fun matrix() {
        val frames = MATRIX_STATES.keys.flatMap { state -> ScreenshotMatrix.review().map { state to it } }
        val step = mutableIntStateOf(0)
        val clipped = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val (state, case) = frames[step.intValue]
            key(step.intValue) {
                ScreenshotFrame(case) {
                    RamfluxTheme(case.dark) {
                        Box(Modifier.fillMaxSize().testTag("matrix")) { requireNotNull(MATRIX_STATES[state]).invoke() }
                    }
                }
            }
        }
        frames.forEachIndexed { index, (state, case) ->
            step.intValue = index
            Snapshot.sendApplyNotifications()
            repeat(3) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag("matrix").captureRoboImage("build/outputs/shell-matrix/$state-${case.label}.png")
            if (state in SECURITY_STATES) compose.clippedTexts().forEach { clipped += "$state\t${case.label}\t$it" }
        }
        // Security text must wrap, never clip. Reviewed clipping (Kit fixed heights, see the file) is
        // reported; any other clipped text fails.
        // Roborazzi writes no image in a plain test run, so the folder may not exist yet.
        val report = File("build/outputs/shell-matrix/clipped-text.tsv")
        report.parentFile.mkdirs()
        report.writeText(clipped.joinToString("\n", postfix = "\n"))
        val known = requireNotNull(javaClass.classLoader).getResource("shell-matrix/known-clipped-text.tsv").readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        println("CLIPPED_TEXT known=${clipped.count { it in known }} new=${clipped.count { it !in known }}")
        assertEquals("security text clipped outside the reviewed list", emptyList<String>(), clipped.filterNot { it in known })
    }
}

/** System bars of the 393x852 design frame (mobile-kit-tokens.json frame.mobile). */
private val BOARD_INSETS = WindowInsets(top = 59.dp, bottom = 34.dp)

private const val ACCOUNT = ConversationBoards.ACCOUNT

private val B02_MESSAGES = persistentListOf(
    MessageItem("m1", "林悦", false, false, UiText.Data("新版资料我整理好了，你有空时看一下。"), UiText.Data("10:21")),
    MessageItem("m2", ACCOUNT, true, false, UiText.Data("收到，我会把问题逐项回复。"), UiText.Data("10:23")),
    MessageItem("m3", "林悦", false, false, UiText.Data("好的，附件里的版本说明也请一起看。"), UiText.Data("10:24"))
)

private val CHAT_B02 = ChatState(conversationId = "lin-yue", title = UiText.Data("林悦"), rows = B02_MESSAGES, loaded = true, canSend = true)
private val CHAT_B02E = ChatState(conversationId = "lin-yue", title = UiText.Data("林悦"), loaded = true, canSend = true)

/** Shell fixture: which tab and stack is shown, and the state of every tab screen. */
private data class ShellFixture(
    val tab: Int = 0,
    val pushed: NavKey? = null,
    val conversations: ConversationsState = ConversationBoards.list,
    val chat: ChatState = CHAT_B02,
    val groups: GroupsState = GroupsState(page = GroupsPage.LIST, account = ACCOUNT, loaded = true),
    val agents: AgentsState = AgentsState()
)

private fun EntryProviderScope<NavKey>.fixtureEntries(fixture: ShellFixture) {
    entry<ConversationsRoute>(metadata = pane(PaneRole.LIST)) { ConversationsScreen(fixture.conversations, {}, rooted = true) }
    entry<ChatRoute>(metadata = pane(PaneRole.DETAIL)) { ChatScreen(fixture.chat, {}) }
    entry<GroupsRoute>(metadata = pane(PaneRole.LIST)) { GroupsScreen(fixture.groups, {}, rooted = true) }
    entry<AgentsRoute> { AgentsScreen(fixture.agents, {}, rooted = true) }
    entry<MoreRoute> { MoreHub(DEFAULT_SHELL_ORDER, "", {}, {}, {}) }
    entry<NavigationEditorRoute> { NavigationEditor(DEFAULT_SHELL_ORDER, "", {}, {}, {}) }
    entry<SettingsRoute> { SettingsScreen(SettingsState(accountLabel = ACCOUNT), {}) }
}

@Composable
private fun FixtureShell(fixture: ShellFixture, insets: WindowInsets = BOARD_INSETS) {
    // The fixture state is the navigator's initial state, built before the shell composes; nothing
    // is mutated during composition.
    val navigator = remember(fixture) {
        TopLevelNavigator(SHELL_TAB_ROOTS.map { mutableStateListOf<NavKey>(it) }, mutableIntStateOf(0)).apply {
            select(fixture.tab)
            fixture.pushed?.let(::openDetail)
        }
    }
    RamfluxShell(navigator, SHELL_ROUTE_POLICY, {}, insets = insets) { fixtureEntries(fixture) }
}

private val GROUPS_C01 = persistentListOf(
    GroupRow("product", "产品探索", UiText.Quantity(GroupsR.plurals.groups_member_count, 3uL)),
    GroupRow("design", "设计协作", UiText.Data("频道与帖子"))
)

/** One renderer per board key. Boards whose content the state model cannot express render what it can. */
private val BOARDS: Map<String, @Composable () -> Unit> = mapOf(
    "MOB-B01" to { FixtureShell(ShellFixture()) },
    "MOB-B01L" to { FixtureShell(ShellFixture(conversations = ConversationBoards.loading)) },
    "MOB-B01X" to { FixtureShell(ShellFixture(conversations = ConversationBoards.cannotConnect)) },
    "MOB-B12" to { FixtureShell(ShellFixture(conversations = ConversationBoards.empty)) },
    "MOB-B02" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue"))) },
    "MOB-B02E" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue"), chat = CHAT_B02E)) },
    "MOB-C01" to {
        val groups = GroupsState(page = GroupsPage.LIST, account = ACCOUNT, loaded = true, groups = GROUPS_C01)
        FixtureShell(ShellFixture(tab = 1, groups = groups))
    },
    "MOB-C15" to { FixtureShell(ShellFixture(tab = 1)) },
    "MOB-D01" to { FixtureShell(ShellFixture(tab = 2)) },
    "MOB-D01E" to { FixtureShell(ShellFixture(tab = 2)) },
    "MOB-D20" to { FixtureShell(ShellFixture(tab = 2)) },
    "MOB-G01" to { FixtureShell(ShellFixture(tab = 3)) },
    "MOB-G02" to { FixtureShell(ShellFixture(tab = 3, pushed = SettingsRoute)) },
    "MOB-G03" to { FixtureShell(ShellFixture(tab = 3, pushed = NavigationEditorRoute)) },
    "MOB-N01" to {
        OnboardingScreen(OnboardingState(page = OnboardingPage.CREATE, sessionReady = true), {}, contentWindowInsets = BOARD_INSETS)
    },
    "MOB-S01" to { StartupSurface(BOARD_INSETS) { SplashScreen() } },
    "MOB-S02" to { StartupSurface(BOARD_INSETS) { GatewayScreen({}, {}) } },
    "MOB-S18" to { StartupSurface(BOARD_INSETS) { WelcomeScreen({}, {}, {}, {}) } },
    "MOB-A01" to { StartupSurface(BOARD_INSETS) { UnlockEntry(ACCOUNT, "https://work.example", {}, {}, {}) } },
    "MOB-A02" to { StartupSurface(BOARD_INSETS) { UnlockDetails(ACCOUNT, {}, {}) } }
)

/** Matrix states: the messages tab with a conversation open (two panes from 700 dp) and the unlock screens. */
private val MATRIX_STATES: Map<String, @Composable () -> Unit> = mapOf(
    "messages-chat" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue")), WindowInsets(0, 0, 0, 0)) },
    "unlock-entry" to { StartupSurface(WindowInsets(0, 0, 0, 0)) { UnlockEntry(ACCOUNT, "https://work.example", {}, {}, {}) } },
    "unlock-details" to { StartupSurface(WindowInsets(0, 0, 0, 0)) { UnlockDetails(ACCOUNT, {}, {}) } }
)

private val SECURITY_STATES = setOf("unlock-entry", "unlock-details")
