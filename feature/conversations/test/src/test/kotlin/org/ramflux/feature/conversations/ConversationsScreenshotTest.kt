// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class ConversationsScreenshotTest(private val width: Int, private val dark: Boolean, private val kind: String) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}")
        fun cases(): List<Array<Any>> = listOf(400, 900).flatMap { width ->
            listOf(false, true).flatMap { dark ->
                listOf("loading", "error", "empty", "account", "rows").map { arrayOf(width, dark, it) }
            }
        }
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> ConversationsState(loading = true)
            "error" -> ConversationsState(problem = "Private data could not be read.")
            "empty" -> ConversationsState(loaded = true)
            "account" -> ConversationsState(accountRequired = true)
            else -> ConversationsState(
                loaded = true,
                rows = persistentListOf(ConversationItem("c", "Project", "Conversation", "7", true)),
                unavailable = "Local search is not available in this version."
            )
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 500.dp))) {
                RamfluxTheme(dark) { ConversationsScreen(state, {}) }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/conversations-$width-$dark-$kind.png")
    }
}
