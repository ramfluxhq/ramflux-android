// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
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
class LanguageStartupTest(private val position: Int) {
    companion object {
        /** Positions in the app's language list: the first offered language, and the last one. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "startup-{0}")
        fun cases(): List<Array<Any>> = listOf(arrayOf<Any>(0), arrayOf<Any>(-1))
    }

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The languages come from the app's generated locale config, never from a list in the test. */
    private val languages = appLanguages(context.resources, R.xml._generated_res_locale_config)

    /** Host labels a language shows, resolved from resources (follow-system resolves to the test's en-US). */
    private fun labels(language: AppLanguage): List<String> {
        val configuration = Configuration(context.resources.configuration)
        if (language != AppLanguage.SYSTEM) configuration.setLocales(LocaleList.forLanguageTags(language.tag))
        val localized = context.createConfigurationContext(configuration)
        return listOf(R.string.shell_tab_messages, R.string.startup_continue, R.string.startup_starting).map(localized::getString)
    }

    @Test fun actualHiltShellStartsAppliesAndRestoresEveryLaunchLocale() {
        assertTrue("the app offers follow-system and at least two languages", languages.size >= 3)
        val initial = if (position < 0) languages.last() else languages[position]
        compose.waitUntil(5_000) { !compose.activity.language.language.value.loading }
        fun assertShell(language: AppLanguage) {
            compose.waitUntil(5_000) { compose.activity.language.language.value.value == language }
            val (shell, continueLabel, starting) = labels(language)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(shell).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(continueLabel).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithText(starting).fetchSemanticsNodes().isNotEmpty()
            }
            val shellCount = compose.onAllNodesWithText(shell).fetchSemanticsNodes().size
            if (shellCount > 0) assertEquals(2, shellCount)
            val mine = labels(language).toSet()
            for (other in languages.flatMap(::labels).toSet() - mine) {
                compose.onAllNodesWithText(other).assertCountEquals(0)
            }
        }
        runBlocking { withTimeout(5_000) { assertTrue(compose.activity.language.setLanguage(initial) is ClientResult.Ready) } }
        compose.activityRule.scenario.recreate()
        assertShell(initial)
        val shell = ViewModelProvider(compose.activity)[AppShellViewModel::class.java]
        for (language in languages + initial) {
            runBlocking { withTimeout(5_000) { assertTrue(compose.activity.language.setLanguage(language) is ClientResult.Ready) } }
            assertShell(language)
            assertSame(shell, ViewModelProvider(compose.activity)[AppShellViewModel::class.java])
        }
        compose.activityRule.scenario.recreate()
        assertShell(initial)
        // The choice lives in its single source of truth: the platform per-app language on API 33+,
        // the device-local store before that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertEquals(initial.tag, context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
        } else {
            val restored = AndroidClientData(context, "a".repeat(64), languages).language
            runBlocking {
                withTimeout(5_000) {
                    restored.language.first { !it.loading }.also { assertEquals(initial, it.value) }
                }
            }
        }
    }
}
