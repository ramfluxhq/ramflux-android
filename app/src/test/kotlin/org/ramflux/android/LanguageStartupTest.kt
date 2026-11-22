// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android
import android.app.LocaleManager
import android.content.Context
import android.os.Build
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
            val chinese = language == AppLanguage.SIMPLIFIED_CHINESE
            val shell = if (chinese) "消息" else "Messages"
            val continueLabel = if (chinese) "继续" else "Continue"
            val starting = if (chinese) "正在启动" else "Starting up"
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(shell).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(continueLabel).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(starting).fetchSemanticsNodes().isNotEmpty()
            }
            val shellCount = compose.onAllNodesWithText(shell).fetchSemanticsNodes().size
            if (shellCount > 0) assertEquals(2, shellCount)
            val otherShell = if (chinese) "Messages" else "消息"
            val otherContinue = if (chinese) "Continue" else "继续"
            val otherStarting = if (chinese) "Starting up" else "正在启动"
            compose.onAllNodesWithText(otherShell).assertCountEquals(0)
            compose.onAllNodesWithText(otherContinue).assertCountEquals(0)
            compose.onAllNodesWithText(otherStarting).assertCountEquals(0)
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
        // The choice lives in its single source of truth: the platform per-app language on API 33+,
        // the device-local store before that.
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(initial.tag, context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        } else {
            val restored = AndroidClientData(context, "a".repeat(64)).language
            runBlocking {
                withTimeout(5_000) {
                    restored.language.first { !it.loading }.also { assertEquals(initial, it.value) }
                }
            }
        }
    }
}
