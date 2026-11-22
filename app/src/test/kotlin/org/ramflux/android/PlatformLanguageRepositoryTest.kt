// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.AppLanguage
import org.ramflux.data.ClientResult
import org.ramflux.data.LanguageRepository
import org.ramflux.data.LanguageSnapshot
import org.ramflux.data.appLanguages
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class StoredLanguage(initial: AppLanguage, override val languages: List<AppLanguage>) : LanguageRepository {
    override val language = MutableStateFlow(LanguageSnapshot(initial, loading = false))
    override suspend fun setLanguage(value: AppLanguage): ClientResult<Unit> {
        language.value = LanguageSnapshot(value, loading = false)
        return ClientResult.Ready(Unit)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlatformLanguageRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val platform = context.getSystemService(LocaleManager::class.java)

    /** The app's languages, from its generated locale config. */
    private val languages = appLanguages(context.resources, R.xml._generated_res_locale_config)

    private fun settled(repository: LanguageRepository) = runBlocking { withTimeout(5_000) { repository.language.first { !it.loading } } }

    @Test fun thePickerWritesThePlatformPerAppLanguage() {
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM, languages))
        assertEquals(AppLanguage.SYSTEM, settled(repository).value)
        for (choice in languages) {
            assertTrue(runBlocking { repository.setLanguage(choice) } is ClientResult.Ready)
            assertEquals(choice.tag, platform.applicationLocales.toLanguageTags())
            assertEquals(choice, repository.language.value.value)
        }
    }

    @Test fun aChangeInSystemSettingsIsReadBack() {
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM, languages))
        settled(repository)
        val chosen = languages.last()
        platform.applicationLocales = LocaleList.forLanguageTags(chosen.tag)
        context.resources.configuration.let { (context as android.app.Application).onConfigurationChanged(Configuration(it)) }
        assertEquals(chosen, repository.language.value.value)
    }

    @Test fun theLanguagesAreTheGeneratedLocaleConfig() {
        assertEquals(AppLanguage.SYSTEM, languages.first())
        val folders = languages.drop(1).map { it.tag }
        assertEquals(folders.distinct(), folders)
        // The default resource locale (resources.properties) is always offered.
        assertTrue(folders.contains(context.resources.getXml(R.xml._generated_res_locale_config).let { parser ->
            var default: String? = null
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale-config") {
                    default = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "defaultLocale")
                }
            }
            default
        }))
    }

    @Test fun aPlatformLocaleThePickerCannotNameFollowsTheSystemWithANotice() {
        val unknown = listOf("fr", "de", "ja", "sw").first { tag -> languages.none { it.tag == tag } }
        platform.applicationLocales = LocaleList.forLanguageTags(unknown)
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM, languages))
        val snapshot = settled(repository)
        assertEquals(AppLanguage.SYSTEM, snapshot.value)
        assertNotNull(snapshot.problem)
    }

    @Test fun aChoiceFromTheDeviceStoreMovesToThePlatformOnce() {
        val chosen = languages.last()
        val stored = StoredLanguage(chosen, languages)
        val repository = appLanguageRepository(context, stored)
        runBlocking { withTimeout(5_000) { repository.language.first { !it.loading && it.value == chosen } } }
        assertEquals(chosen.tag, platform.applicationLocales.toLanguageTags())
        assertEquals(AppLanguage.SYSTEM, stored.language.value.value)
    }
}
