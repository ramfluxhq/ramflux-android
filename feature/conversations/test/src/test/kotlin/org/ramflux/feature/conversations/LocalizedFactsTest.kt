// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.conversations
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.intl.LocaleList
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.designsystem.*
import org.ramflux.feature.conversations.impl.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizedFactsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun datesFollowCurrentLocaleAndPeerDataIsNeverATranslationKey() {
        var tag by mutableStateOf("en")
        var date = ""
        val peer = "ui_no_conversations"
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(tag))) {
                val rendered = UiText.DateTime(1700000000).render()
                SideEffect { date = rendered }
                Text(UiText.Data(peer).render())
            }
        }
        val englishDate = date
        compose.runOnIdle { tag = "zh-CN" }
        compose.waitForIdle()
        assertNotEquals(englishDate, date)
        compose.onNodeWithText(peer).assertExists()
    }

    @Test fun pseudoLocaleExpandsHostTextWithoutChangingState() {
        var tag by mutableStateOf("en")
        var label = ""
        val text = uiText(R.string.ui_conversations_will_appear_here_when_available)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(tag))) {
                val current = text.render()
                SideEffect { label = current }
                Text(current)
            }
        }
        val english = label
        compose.runOnIdle { tag = "en-XA" }
        compose.waitForIdle()
        assertNotEquals(english, label)
        assertTrue(label.length > english.length)
    }
}
