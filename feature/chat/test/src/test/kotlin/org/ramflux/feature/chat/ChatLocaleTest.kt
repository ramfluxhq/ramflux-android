// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.chat
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.RamfluxTheme
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatLocaleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chineseLocaleRendersHostResourcesAndSwitchDoesNotEmitBusinessActions() {
        var locale by mutableStateOf("en")
        var actions = 0
        val stamp = 1700000000L
        val history = FakeChatHistory().apply {
            heading = ClientResult.Ready(ConversationHeader("Actual title"))
            reading = { ClientResult.Ready(listOf(MessageSummary("one", "peer", false, false, "approved", stamp))) }
        }
        val vm = ChatViewModel(history, FakeChatAccounts(), FakeChatSession())
        vm.onAction(ChatAction.Load("a", "c"))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                val state by vm.state.collectAsState()
                RamfluxTheme {
                    ChatScreen(state, {
                        actions++
                        vm.onAction(it)
                    })
                }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.loaded }
        val reads = history.reads
        fun formatted(language: String): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(java.util.Locale.forLanguageTag(language))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(stamp))
        compose.onNodeWithText("Actual title").assertExists()
        compose.onNodeWithText(formatted("en")).assertExists()
        compose.runOnIdle { locale = "zh-CN" }
        compose.onNodeWithText("Actual title").assertExists()
        compose.onNodeWithText(formatted("zh-CN")).assertExists()
        compose.runOnIdle {
            assertEquals(0, actions)
            assertEquals(reads, history.reads)
        }
    }
}
