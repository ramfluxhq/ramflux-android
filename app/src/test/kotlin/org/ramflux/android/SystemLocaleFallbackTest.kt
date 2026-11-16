// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.ramflux.data.AppLanguage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class ClearLanguagePrefs : TestWatcher() {
    override fun starting(description: Description) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().clear().commit()
    }
}

/** Fresh installs keep System. An unsupported device locale resolves the English resources. */
@RunWith(RobolectricTestRunner::class)
@Config(application = RamfluxApplication::class, sdk = [35], qualifiers = "fr-rFR")
class UnsupportedSystemLocaleTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ClearLanguagePrefs()).around(compose)

    @Test fun freshSystemSettingOnAnUnsupportedLocaleShowsEnglish() {
        compose.waitUntil(5_000) { !compose.activity.language.language.value.loading }
        assertEquals(AppLanguage.SYSTEM, compose.activity.language.language.value.value)
        compose.onNodeWithText("Get started").assertExists()
        compose.onNodeWithText("开始使用").assertDoesNotExist()
    }
}

/** A supported device locale is followed while the saved setting stays System. */
@RunWith(RobolectricTestRunner::class)
@Config(application = RamfluxApplication::class, sdk = [35], qualifiers = "zh-rCN")
class SupportedSystemLocaleTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ClearLanguagePrefs()).around(compose)

    @Test fun freshSystemSettingOnChineseShowsChinese() {
        compose.waitUntil(5_000) { !compose.activity.language.language.value.loading }
        assertEquals(AppLanguage.SYSTEM, compose.activity.language.language.value.value)
        compose.onNodeWithText("开始使用").assertExists()
        compose.onNodeWithText("Get started").assertDoesNotExist()
    }
}
