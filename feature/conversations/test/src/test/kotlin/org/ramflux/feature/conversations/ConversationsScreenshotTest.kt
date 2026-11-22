// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.ramflux.designsystem.UiText
import org.ramflux.designsystem.uiText
import org.ramflux.feature.conversations.impl.R
import org.ramflux.testing.fixtures.ConversationBoards
import org.ramflux.testing.screenshot.ScreenshotCase
import org.ramflux.testing.screenshot.ScreenshotFrame
import org.ramflux.testing.screenshot.ScreenshotMatrix
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One parameterised matrix: states x the shared screenshot cases. The B01 family renders at the
 * board frame with board content and the board's system bar insets in every matrix locale and
 * theme (the app's board test adds the shell around it and is the one compared with the design);
 * the list renders over the full review matrix; secondary states render at the phone frame.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "${ScreenshotMatrix.WINDOW_QUALIFIERS}-mdpi")
class ConversationsScreenshotTest(private val kind: String, private val case: ScreenshotCase) {
    @get:Rule val compose = createComposeRule()

    companion object {
        private val boardKinds = ConversationBoards.boards.keys.toList()
        private val secondary = listOf("account", "unavailable")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun cases(): List<Array<Any>> = ScreenshotMatrix.parameters(boardKinds + "rows" + secondary) { kind ->
            if (kind == "rows") ScreenshotMatrix.review() else ScreenshotMatrix.compact()
        }
    }

    @Test fun screen() {
        val board = ConversationBoards.boards[kind]
        val state = board ?: when (kind) {
            "account" -> ConversationsState(accountRequired = true)
            "unavailable" -> ConversationsState(
                loaded = true,
                rows = persistentListOf(
                    ConversationItem("c", "Project", uiText(R.string.ui_conversation), "7", true, UiText.Data("10:30"))
                ),
                unavailable = uiText(R.string.ui_value_is_not_available_in_this_version, uiText(R.string.ui_local_search))
            )
            else -> ConversationBoards.list
        }
        // Board frames carry the design's system bars (59 dp status bar, 34 dp home indicator).
        val insets = if (board != null) WindowInsets(top = 59.dp, bottom = 34.dp) else WindowInsets(0, 0, 0, 0)
        compose.setContent {
            ScreenshotFrame(case) {
                RamfluxTheme(case.dark) {
                    ConversationsScreen(state, {}, rooted = true, modifier = Modifier.fillMaxSize().windowInsetsPadding(insets))
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/conversations-$kind-${case.label}.png")
    }
}
