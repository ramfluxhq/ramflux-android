// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.ramflux.feature.appshell.AppShellViewModel
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual production Application/Activity, Hilt component and shell; no replacement factory or direct VM construction. */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = RamfluxApplication::class, sdk = [28, 35], qualifiers = "en-rUS")
class LanguageStartupTest(private val initial: AppLanguage) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "startup-{0}")
        fun cases(): List<Array<Any>> = AppLanguage.entries.map { arrayOf(it) }
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualHiltShellStartsAppliesAndRestoresEveryLaunchLocale() {
        compose.waitUntil(5_000) { !compose.activity.language.language.value.loading }
        fun assertShell(language: AppLanguage) {
            compose.waitUntil(5_000) { compose.activity.language.language.value.value == language }
            compose.onNodeWithText(if (language == AppLanguage.SIMPLIFIED_CHINESE) "开始使用" else "Get started").assertExists()
        }
        runBlocking { withTimeout(5_000) { assertTrue(compose.activity.language.setLanguage(initial) is ClientResult.Ready) } }
        compose.activityRule.scenario.recreate()
        assertShell(initial)
        val shell = ViewModelProvider(compose.activity)[AppShellViewModel::class.java]
        for (language in listOf(AppLanguage.SIMPLIFIED_CHINESE, AppLanguage.ENGLISH, AppLanguage.SYSTEM, initial)) {
            runBlocking { withTimeout(5_000) { assertTrue(compose.activity.language.setLanguage(language) is ClientResult.Ready) } }
            assertShell(language)
            assertSame(shell, ViewModelProvider(compose.activity)[AppShellViewModel::class.java])
        }
        compose.activityRule.scenario.recreate()
        assertShell(initial)
        val restored = AndroidClientData(ApplicationProvider.getApplicationContext(), "a".repeat(64)).language
        runBlocking {
            withTimeout(5_000) {
                restored.language.first { !it.loading }.also { assertEquals(initial, it.value) }
            }
        }
    }
}
