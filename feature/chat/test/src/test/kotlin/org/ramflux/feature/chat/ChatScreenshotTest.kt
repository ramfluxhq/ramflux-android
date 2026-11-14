// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
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
import org.ramflux.feature.chat.impl.R
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1000dp-h1200dp-mdpi")
class ChatScreenshotTest(private val width: Int, private val dark: Boolean, private val kind: String, private val localeTag: String) {
    @get:Rule val compose = createComposeRule()
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-dark{1}-{2}-locale{3}")
        fun cases(): List<Array<Any>> = baseCases().flatMap { row ->
            listOf("en", "zh-CN", "en-XA").map { tag -> Array<Any>(row.size + 1) { index -> if (index < row.size) row[index] else tag } }
        }
        private fun baseCases(): List<Array<Any>> = listOf(400, 900).flatMap { width ->
            listOf(false, true).flatMap { dark ->
                listOf("loading", "error", "empty", "rows", "group").map { arrayOf(width, dark, it) }
            }
        }
    }

    @Test fun screen() {
        val state = when (kind) {
            "loading" -> ChatState(loading = true)
            "error" -> ChatState(problem = uiText(R.string.ui_unable_to_continue))
            "empty" -> ChatState(loaded = true)
            else -> ChatState(
                conversationId = "conversation-id",
                groupId = if (kind == "group") "group-id" else null,
                title = UiText.Data("Project"),
                loaded = true,
                rows = persistentListOf(
                    MessageItem("one", "Peer", false, false, UiText.Data("Approved message content."), UiText.DateTime(1700000000)),
                    CallItem("call", UiText.DateTime(1700000001)),
                    MessageItem("two", "Self", true, false, UiText.Data("Own group message"), UiText.DateTime(1700000002))
                ),
                unavailable = uiText(R.string.ui_sending_unavailable_value, uiText(R.string.ui_the_sdk_operation_is_not_available_in_this))
            )
        }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(localeTag))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 500.dp))) {
                    RamfluxTheme(dark) { ChatScreen(state, {}) }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/chat-$width-$dark-$kind-$localeTag.png")
    }
}
