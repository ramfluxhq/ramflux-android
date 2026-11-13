// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
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
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class ConversationsScreenshotTest(
    private val width: Int,
    private val dark: Boolean,
    private val kind: String,
    private val localeTag: String
) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-locale{3}")
        fun cases(): List<Array<Any>> = baseCases().flatMap { row ->
            listOf("en", "zh-CN", "en-XA").map { tag -> Array<Any>(row.size + 1) { index -> if (index < row.size) row[index] else tag } }
        }
        private fun baseCases(): List<Array<Any>> = listOf(400, 900).flatMap { width ->
            listOf(false, true).flatMap { dark ->
                listOf("loading", "error", "empty", "account", "rows").map { arrayOf(width, dark, it) }
            }
        }
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> ConversationsState(accountLabel = "Work", loading = true)
            "error" -> ConversationsState(accountLabel = "Work", problem = uiText(R.string.ui_unable_to_continue))
            "empty" -> ConversationsState(loaded = true)
            "account" -> ConversationsState(accountRequired = true)
            else -> ConversationsState(
                loaded = true,
                rows = persistentListOf(
                    ConversationItem("c", "Project", uiText(R.string.ui_conversation), "7", true, UiText.Data("10:30"))
                ),
                unavailable = uiText(R.string.ui_value_is_not_available_in_this_version, uiText(R.string.ui_local_search))
            )
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(localeTag))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 500.dp))) {
                    RamfluxTheme(dark) { ConversationsScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/conversations-$width-$dark-$kind-$localeTag.png")
    }
}
