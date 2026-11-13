// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.appshell
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
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
class ThemePersistenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun namedThemePersistsAndSystemThemeSwitchesWithoutReplacingComposition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().clear().commit()
        val preferences = AndroidClientData(context, "a".repeat(64)).appearance
        runBlocking { withTimeout(5_000) { preferences.appearance.first { !it.loading } } }
        var systemDark by mutableStateOf(false)
        var background = Color.Unspecified
        var identity: Any? = null
        compose.setContent {
            val selection by preferences.appearance.collectAsState()
            val remembered = remember { Any() }
            RamfluxTheme(dark = systemDark, themeId = selection.themeId) {
                val current = MaterialTheme.colorScheme.background
                SideEffect {
                    background = current
                    identity = remembered
                }
            }
        }
        val original = identity
        assertEquals(requireNotNull(ThemeCatalog.find("light")).tokens.bg, background)
        runBlocking { withTimeout(5_000) { assertTrue(preferences.setNamedTheme("dark") is ClientResult.Ready) } }
        compose.waitForIdle()
        assertEquals(requireNotNull(ThemeCatalog.find("dark")).tokens.bg, background)
        assertSame(original, identity)
        val restored = AndroidClientData(context, "a".repeat(64)).appearance
        runBlocking { withTimeout(5_000) { assertEquals("dark", restored.appearance.first { !it.loading }.themeId) } }
        runBlocking { withTimeout(5_000) { assertTrue(preferences.setAppearance(Appearance.SYSTEM) is ClientResult.Ready) } }
        compose.waitForIdle()
        assertNull(preferences.appearance.value.themeId)
        compose.runOnIdle { systemDark = true }
        compose.waitForIdle()
        assertEquals(requireNotNull(ThemeCatalog.find("dark")).tokens.bg, background)
        assertSame(original, identity)
        compose.runOnIdle { systemDark = false }
        compose.waitForIdle()
        assertEquals(requireNotNull(ThemeCatalog.find("light")).tokens.bg, background)
    }

    @Test fun shippedThemeFilesMatchTheCanonicalGeneratedTokenSets() {
        assertEquals(org.ramflux.generated.RFDesign.light, requireNotNull(ThemeCatalog.find("light")).tokens)
        assertEquals(org.ramflux.generated.RFDesign.dark, requireNotNull(ThemeCatalog.find("dark")).tokens)
    }
}
