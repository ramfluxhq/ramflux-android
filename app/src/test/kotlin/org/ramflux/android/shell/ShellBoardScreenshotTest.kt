// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.test.core.app.ApplicationProvider
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
import org.ramflux.designsystem.uiText
import org.ramflux.feature.agents.AgentRow
import org.ramflux.feature.agents.AgentsScreen
import org.ramflux.feature.agents.AgentsState
import org.ramflux.feature.agents.api.AgentsRoute
import org.ramflux.feature.agents.impl.R as AgentsR
import org.ramflux.feature.appshell.ShellPage
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.appshell.api.NavigationEditorRoute
import org.ramflux.feature.calls.CallsScreen
import org.ramflux.feature.calls.CallsState
import org.ramflux.feature.calls.api.CallsRoute
import org.ramflux.feature.cards.CardsScreen
import org.ramflux.feature.cards.CardsState
import org.ramflux.feature.cards.api.CardsRoute
import org.ramflux.feature.chat.ChatScreen
import org.ramflux.feature.chat.ChatState
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.contacts.ContactsPage
import org.ramflux.feature.contacts.ContactsScreen
import org.ramflux.feature.contacts.ContactsState
import org.ramflux.feature.contacts.PersonItem
import org.ramflux.feature.contacts.SafetyItem
import org.ramflux.feature.contacts.api.ContactsRoute
import org.ramflux.feature.contacts.impl.R as ContactsR
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
import org.ramflux.feature.permissions.PermissionsScreen
import org.ramflux.feature.permissions.PermissionsState
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.settings.SettingsScreen
import org.ramflux.feature.settings.SettingsState
import org.ramflux.feature.settings.api.SettingsRoute
import org.ramflux.feature.tasks.TasksScreen
import org.ramflux.feature.tasks.TasksState
import org.ramflux.feature.tasks.api.TasksRoute
import org.ramflux.navigation.LocalPaneRole
import org.ramflux.navigation.PaneRole
import org.ramflux.navigation.pane
import org.ramflux.navigation.rememberTopLevelNavigator
import org.ramflux.testing.fixtures.ChatBoards
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
        useBoardClock()
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
        useBoardClock()
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
        // Without recording (a clean build) no capture has created the folder yet.
        File("build/outputs/shell-matrix").mkdirs()
        File("build/outputs/shell-matrix/clipped-text.tsv").writeText(clipped.joinToString("\n", postfix = "\n"))
        val known = requireNotNull(javaClass.classLoader).getResource("shell-matrix/known-clipped-text.tsv").readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }.toSet()
        println("CLIPPED_TEXT known=${clipped.count { it in known }} new=${clipped.count { it !in known }}")
        assertEquals("security text clipped outside the reviewed list", emptyList<String>(), clipped.filterNot { it in known })
    }
}

/** The boards use the 24-hour clock; the chat follows the device setting. */
private fun useBoardClock() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    Settings.System.putString(context.contentResolver, Settings.System.TIME_12_24, "24")
}

/** System bars of the 393x852 design frame (mobile-kit-tokens.json frame.mobile). */
private val BOARD_INSETS = WindowInsets(top = 59.dp, bottom = 34.dp)

private const val ACCOUNT = ConversationBoards.ACCOUNT

/** The display name typed on MOB-N01. */
private const val BOARD_NAME = "林澈"

/** Shell fixture: which tab and stack is shown, and the state of every tab screen. */
private data class ShellFixture(
    val tab: Int = 0,
    val pushed: NavKey? = null,
    val conversations: ConversationsState = ConversationBoards.list,
    val chat: ChatState = ChatBoards.conversation(),
    val groups: GroupsState = GroupsState(page = GroupsPage.LIST, account = ACCOUNT, loaded = true),
    val agents: AgentsState = AgentsState(),
    val contacts: ContactsState = ContactsState(loaded = true)
)

private fun EntryProviderScope<NavKey>.fixtureEntries(fixture: ShellFixture) {
    entry<ConversationsRoute>(metadata = pane(PaneRole.LIST)) { ConversationsScreen(fixture.conversations, {}, rooted = true) }
    entry<ChatRoute>(metadata = pane(PaneRole.DETAIL)) {
        ChatScreen(fixture.chat, {}, rooted = LocalPaneRole.current == PaneRole.DETAIL, clock = ChatBoards.clock)
    }
    entry<GroupsRoute>(metadata = pane(PaneRole.LIST)) { GroupsScreen(fixture.groups, {}, rooted = true) }
    entry<AgentsRoute> { AgentsScreen(fixture.agents, {}, rooted = true) }
    entry<MoreRoute> { MoreHub(DEFAULT_SHELL_ORDER, "", {}, {}, {}) }
    entry<NavigationEditorRoute> { NavigationEditor(DEFAULT_SHELL_ORDER, "", {}, {}, {}) }
    entry<SettingsRoute> { SettingsScreen(SettingsState(accountLabel = ACCOUNT), {}) }
    entry<ContactsRoute> { ContactsScreen(fixture.contacts, {}) }
    entry<TasksRoute> { TasksScreen(TasksState(), {}) }
    entry<CallsRoute> { CallsScreen(CallsState(), {}) }
    entry<CardsRoute> { CardsScreen(CardsState(), {}) }
    entry<PermissionsRoute> { PermissionsScreen(PermissionsState(loading = false), {}) }
}

@Composable
private fun FixtureShell(fixture: ShellFixture, insets: WindowInsets = BOARD_INSETS) {
    val navigator = rememberTopLevelNavigator(SHELL_TAB_ROOTS)
    // Selects the fixture's tab and stack once per navigator, before the first frame.
    remember(navigator) {
        navigator.select(fixture.tab)
        fixture.pushed?.let(navigator::openDetail)
        navigator
    }
    RamfluxShell(navigator, SHELL_ROUTE_POLICY, {}, insets = insets) { fixtureEntries(fixture) }
}

private val GROUPS_C01 = persistentListOf(
    GroupRow("product", "产品探索", UiText.Quantity(GroupsR.plurals.groups_member_count, 3uL)),
    GroupRow("design", "设计协作", UiText.Data("频道与帖子"))
)

/** MOB-D01: three agents; the status line is the record's lifecycle (the board's collaboration state has no SDK field). */
private val AGENTS_D01 = persistentListOf(
    AgentRow("nova", "Nova", "研究搭档", "orbit", uiText(AgentsR.string.agents_status_active), true),
    AgentRow("atlas", "Atlas", "规划助手", "gem", uiText(AgentsR.string.agents_status_active), true),
    AgentRow("mira", "Mira", "写作伙伴", "user-round", uiText(AgentsR.string.agents_status_active), true)
)
private val AGENTS_LIST = AgentsState(loaded = true, rows = AGENTS_D01, loadedCount = AGENTS_D01.size)

/** One renderer per board key. Boards whose content the state model cannot express render what it can. */
private val BOARDS: Map<String, @Composable () -> Unit> = mapOf(
    "MOB-B01" to { FixtureShell(ShellFixture()) },
    "MOB-B01L" to { FixtureShell(ShellFixture(conversations = ConversationBoards.loading)) },
    "MOB-B01X" to { FixtureShell(ShellFixture(conversations = ConversationBoards.cannotConnect)) },
    "MOB-B12" to { FixtureShell(ShellFixture(conversations = ConversationBoards.empty)) },
    "MOB-B02" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue"))) },
    "MOB-B02E" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue"), chat = ChatBoards.empty)) },
    "MOB-C01" to {
        val groups = GroupsState(page = GroupsPage.LIST, account = ACCOUNT, loaded = true, groups = GROUPS_C01)
        FixtureShell(ShellFixture(tab = 1, groups = groups))
    },
    "MOB-C15" to { FixtureShell(ShellFixture(tab = 1)) },
    "MOB-D01" to { FixtureShell(ShellFixture(tab = 2, agents = AGENTS_LIST)) },
    "MOB-D01E" to { FixtureShell(ShellFixture(tab = 2, agents = AGENTS_LIST.copy(rows = persistentListOf(), query = "Orion"))) },
    "MOB-D20" to { FixtureShell(ShellFixture(tab = 2, agents = AgentsState(loaded = true))) },
    "MOB-G01" to { FixtureShell(ShellFixture(tab = 3)) },
    "MOB-G02" to { FixtureShell(ShellFixture(tab = 3, pushed = SettingsRoute)) },
    "MOB-G03" to { FixtureShell(ShellFixture(tab = 3, pushed = NavigationEditorRoute)) },
    "MOB-N01" to {
        OnboardingScreen(
            OnboardingState(page = OnboardingPage.CREATE, sessionReady = true, displayName = BOARD_NAME),
            {},
            modifier = Modifier.windowInsetsPadding(BOARD_INSETS)
        )
    },
    "MOB-S01" to { StartupSurface(BOARD_INSETS) { SplashScreen() } },
    "MOB-S02" to { StartupSurface(BOARD_INSETS) { GatewayScreen({}, {}) } },
    "MOB-S18" to { StartupSurface(BOARD_INSETS) { WelcomeScreen({}, {}, {}, {}) } },
    "MOB-A01" to { StartupSurface(BOARD_INSETS, grouped = true) { UnlockEntry(ACCOUNT, "https://work.example", {}, {}, {}) } },
    "MOB-A02" to { StartupSurface(BOARD_INSETS, grouped = true) { UnlockDetails(ACCOUNT, {}, {}) } },
    "MOB-A07" to { StartupSurface(BOARD_INSETS, grouped = true) { UnlockInfo({}, {}) } }
)

private val NO_INSETS = WindowInsets(0, 0, 0, 0)

/** A contact on its safety page: a 60-digit safety number and a fingerprint. */
private val SAFETY_CONTACT = run {
    val person = PersonItem(
        "peer", "peer-id", "林悦", uiText(ContactsR.string.contacts_unverified), false, false, null, null, null
    )
    ContactsState(
        accountLabel = ACCOUNT,
        loaded = true,
        rows = persistentListOf(person),
        selected = person,
        page = ContactsPage.SAFETY,
        safety = SafetyItem(
            "052417733908182640516279388104529976631840271956",
            "9f3a0c7d51e2b84f",
            uiText(ContactsR.string.contacts_safety_state_unverified),
            verified = false,
            verifiable = true
        )
    )
}

/** Recovery words as borrowed character arrays, as the ceremony lends them. */
private val PHRASE = listOf("apple", "river", "stone", "cloud", "amber", "orbit", "maple", "quartz", "harbor", "tiger", "violet", "zebra")
    .map { it.toCharArray() }

/**
 * Matrix states: the messages tab with a conversation open (two panes from 700 dp), the groups and agents
 * tabs, the other feature screens inside the shell (pushed on the More tab), the root-stack onboarding page
 * with the recovery phrase, the contact safety number and the unlock screens.
 */
private val MATRIX_STATES: Map<String, @Composable () -> Unit> = mapOf(
    "messages-chat" to { FixtureShell(ShellFixture(pushed = ChatRoute("a", "lin-yue")), NO_INSETS) },
    "groups" to {
        val groups = GroupsState(page = GroupsPage.LIST, account = ACCOUNT, loaded = true, groups = GROUPS_C01)
        FixtureShell(ShellFixture(tab = 1, groups = groups), NO_INSETS)
    },
    "agents" to { FixtureShell(ShellFixture(tab = 2, agents = AGENTS_LIST), NO_INSETS) },
    "settings" to { FixtureShell(ShellFixture(tab = 3, pushed = SettingsRoute), NO_INSETS) },
    "contacts-safety" to { FixtureShell(ShellFixture(tab = 3, pushed = ContactsRoute, contacts = SAFETY_CONTACT), NO_INSETS) },
    "tasks" to { FixtureShell(ShellFixture(tab = 3, pushed = TasksRoute), NO_INSETS) },
    "calls" to { FixtureShell(ShellFixture(tab = 3, pushed = CallsRoute), NO_INSETS) },
    "cards" to { FixtureShell(ShellFixture(tab = 3, pushed = CardsRoute), NO_INSETS) },
    "permissions" to { FixtureShell(ShellFixture(tab = 3, pushed = PermissionsRoute()), NO_INSETS) },
    "onboarding-phrase" to {
        ShellPage { modifier ->
            OnboardingScreen(
                OnboardingState(page = OnboardingPage.PHRASE, sessionReady = true, phraseSlots = PHRASE.size),
                {},
                drawPhrase = { index, block -> block(PHRASE[index]) },
                modifier = modifier
            )
        }
    },
    "unlock-entry" to { StartupSurface(NO_INSETS) { UnlockEntry(ACCOUNT, "https://work.example", {}, {}, {}) } },
    "unlock-details" to { StartupSurface(NO_INSETS) { UnlockDetails(ACCOUNT, {}, {}) } }
)

private val SECURITY_STATES = setOf("unlock-entry", "unlock-details", "contacts-safety", "onboarding-phrase")
