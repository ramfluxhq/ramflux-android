// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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
class SettingsThemeIntegrationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsTapChangesPaletteAndPersistsWithoutRepeatingAccountReads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AndroidClientData(context, "a".repeat(64)).appearance
        runBlocking { withTimeout(5_000) { preferences.appearance.first { !it.loading } } }
        val accounts = FakeSettingsAccounts().apply { result = ClientResult.Ready(AccountCatalog(emptyList(), null)) }
        val devices = FakeDeviceDirectory()
        val vm = SettingsViewModel(accounts, devices, FakeAccountStatus(), preferences, FakeSettingsSession(), FakeSettingsLanguage())
        vm.onAction(SettingsAction.Page(SettingsPage.APPEARANCE))
        var background = Color.Unspecified
        var identity: Any? = null
        compose.setContent {
            val state by vm.state.collectAsState()
            val remembered = remember { Any() }
            RamfluxTheme(themeId = state.selectedTheme) {
                val color = MaterialTheme.colorScheme.background
                SideEffect {
                    background = color
                    identity = remembered
                }
                SettingsScreen(state, vm::onAction)
            }
        }
        val original = identity
        val accountReads = accounts.reads
        compose.onNodeWithTag("theme:dark").performClick()
        compose.waitUntil(5_000) { preferences.appearance.value.themeId == "dark" }
        compose.waitForIdle()
        compose.onNodeWithTag("theme:dark").assertIsSelected()
        assertEquals(requireNotNull(ThemeCatalog.find("dark")).tokens.bg, background)
        assertSame(original, identity)
        assertEquals(accountReads, accounts.reads)
        assertEquals(0, devices.reads)
        val restored = AndroidClientData(context, "a".repeat(64)).appearance
        runBlocking { withTimeout(5_000) { assertEquals("dark", restored.appearance.first { !it.loading }.themeId) } }
    }
}
