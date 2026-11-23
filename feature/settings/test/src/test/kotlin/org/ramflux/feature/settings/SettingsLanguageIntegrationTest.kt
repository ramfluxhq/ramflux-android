// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.designsystem.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsLanguageIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun realLanguageTapApplyChangesHostLabelsAndPersistsWhilePeerTextAndReadsStayUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().clear().commit()
        val language = AndroidClientData(context, "a".repeat(64), TEST_LANGUAGES).language
        runBlocking { withTimeout(5_000) { language.language.first { !it.loading } } }
        val accounts = FakeSettingsAccounts()
        val devices = FakeDeviceDirectory()
        val status = FakeAccountStatus()
        val vm = SettingsViewModel(accounts, devices, status, FakeSettingsAppearance(), FakeSettingsSession(), language, FakeAccountSecurity())
        vm.onAction(SettingsAction.Page(SettingsPage.LANGUAGE))
        var identity: Any? = null
        compose.setContent {
            val state by vm.state.collectAsState()
            val locale by language.language.collectAsState()
            val remembered = remember { Any() }
            SideEffect { identity = remembered }
            AppLanguageProvider(locale.value.tag) {
                RamfluxTheme {
                    Text(UiText.Data("settings_language").render())
                    SettingsScreen(state, vm::onAction)
                }
            }
        }
        compose.waitUntil(5_000) { vm.state.value.accountLabel != null }
        val original = identity
        val accountReads = accounts.reads
        val deviceReads = devices.reads
        val statusReads = status.reads
        // Every configured language is offered, including one this module has no resources for.
        // The fifteen launch languages make the list longer than the window: scroll the lazy list to the row first.
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("language:de")
        compose.onNodeWithTag("language:de").assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("language:zh-CN")
        compose.onNodeWithTag("language:zh-CN").performScrollTo().performClick()
        compose.onNodeWithTag("language:zh-CN").assertIsSelected()
        assertEquals(AppLanguage.SYSTEM, language.language.value.value)
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("apply-language")
        compose.onNodeWithText("Apply language").performClick()
        compose.waitUntil(5_000) { language.language.value.value == AppLanguage("zh-CN") }
        compose.onNodeWithText("语言").assertExists()
        compose.onNodeWithText("应用语言").assertIsNotEnabled()
        compose.onNodeWithText("settings_language").assertExists()
        val restored = AndroidClientData(context, "a".repeat(64), TEST_LANGUAGES).language
        runBlocking { withTimeout(5_000) { assertEquals(AppLanguage("zh-CN"), restored.language.first { !it.loading }.value) } }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("language:en")
        compose.onNodeWithTag("language:en").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("apply-language")
        compose.onNodeWithText("应用语言").performClick()
        compose.waitUntil(5_000) { language.language.value.value == AppLanguage("en") }
        compose.onNodeWithText("Language").assertExists()
        compose.onNodeWithText("settings_language").assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("language:system")
        compose.onNodeWithTag("language:").performClick()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("apply-language")
        compose.onNodeWithText("Apply language").performClick()
        compose.waitUntil(5_000) { language.language.value.value == AppLanguage.SYSTEM }
        assertSame(original, identity)
        assertEquals(accountReads, accounts.reads)
        assertEquals(deviceReads, devices.reads)
        assertEquals(statusReads, status.reads)
    }
}
