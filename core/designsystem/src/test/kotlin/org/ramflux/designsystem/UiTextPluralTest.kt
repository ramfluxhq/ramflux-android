// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.text.PluralRules
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.core.designsystem.R
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plural selection goes through the platform quantity rules for every CLDR category.
 *
 * The test resource set answers each quantity string with the CLDR category the platform rules select for
 * the configured locale, standing in for a `plurals` file that defines all six categories. Unit tests do
 * not merge an Android `res` folder of the test source set, so the set is a [Resources] override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiTextPluralTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arabicReachesAllSixCategories() {
        val cases = linkedMapOf(
            0uL to "zero",
            1uL to "one",
            2uL to "two",
            3uL to "few",
            10uL to "few",
            11uL to "many",
            99uL to "many",
            100uL to "other",
            102uL to "other",
            103uL to "few",
            111uL to "many",
            ULong.MAX_VALUE to "many"
        )
        assertEquals(cases.values.toList(), categories("ar", cases.keys.toList()))
    }

    @Test fun russianUsesOneFewManyByLastDigits() {
        val cases = linkedMapOf(
            0uL to "many",
            1uL to "one",
            2uL to "few",
            4uL to "few",
            5uL to "many",
            11uL to "many",
            12uL to "many",
            21uL to "one",
            22uL to "few",
            111uL to "many",
            1001uL to "one",
            ULong.MAX_VALUE to "many"
        )
        assertEquals(cases.values.toList(), categories("ru", cases.keys.toList()))
    }

    @Test fun englishZeroIsOtherAndOneIsOne() {
        assertEquals(listOf("other", "one", "other"), categories("en", listOf(0uL, 1uL, 2uL)))
    }

    @Test fun largeQuantitiesKeepTheDigitsThatDecideTheCategory() {
        // Expected categories follow the CLDR integer rules for the true value.
        val expected = linkedMapOf(
            Int.MAX_VALUE.toULong() + 1uL to mapOf("ar" to "many", "ru" to "many", "pl" to "many", "fr" to "other", "en" to "other"),
            4_000_000_021uL to mapOf("ar" to "many", "ru" to "one", "pl" to "many", "fr" to "other", "en" to "other"),
            4_000_000_103uL to mapOf("ar" to "few", "ru" to "few", "pl" to "few", "fr" to "other", "en" to "other"),
            5_000_000_000uL to mapOf("ar" to "other", "ru" to "many", "pl" to "many", "fr" to "many", "en" to "other")
        )
        expected.forEach { (value, byLocale) ->
            val mapped = pluralQuantity(value)
            assertEquals(value % 1_000_000uL, mapped.toULong() % 1_000_000uL)
            check(mapped >= 1_000_000) { "mapped $mapped" }
            byLocale.forEach { (tag, category) ->
                val rules = PluralRules.forLocale(java.util.Locale.forLanguageTag(tag))
                assertEquals("$tag $value", category, rules.select(mapped.toDouble()))
            }
        }
        assertEquals(7, pluralQuantity(7uL))
    }

    @Test fun theFormattedCountIsPassedWithTheCategory() {
        var rendered = ""
        compose.setContent {
            Localized("en") { rendered = UiText.Quantity(R.plurals.unread_messages, 1234uL).render() }
        }
        compose.runOnIdle { assertEquals("other|1,234", rendered) }
    }

    private fun categories(tag: String, values: List<ULong>): List<String> {
        val out = mutableListOf<String>()
        compose.setContent {
            Localized(tag) {
                out.clear()
                values.forEach { out += UiText.Quantity(R.plurals.unread_messages, it).render().substringBefore('|') }
            }
        }
        compose.waitForIdle()
        return out.toList()
    }

    @androidx.compose.runtime.Composable
    private fun Localized(tag: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        val base = LocalContext.current
        val configuration = Configuration(base.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) }
        val context = CategoryContext(base, configuration)
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration, content = content)
    }

    private class CategoryContext(base: Context, configuration: Configuration) : ContextWrapper(base) {
        private val categories = CategoryResources(base.resources, configuration)
        override fun getResources(): Resources = categories
    }

    @Suppress("DEPRECATION")
    private class CategoryResources(base: Resources, configuration: Configuration) :
        Resources(base.assets, base.displayMetrics, configuration) {
        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
            PluralRules.forLocale(configuration.locales[0]).select(quantity.toDouble()) + "|" + formatArgs.joinToString()
    }
}
