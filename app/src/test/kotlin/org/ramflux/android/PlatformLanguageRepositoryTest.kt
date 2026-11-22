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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class StoredLanguage(initial: AppLanguage) : LanguageRepository {
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

    private fun settled(repository: LanguageRepository) = runBlocking { withTimeout(5_000) { repository.language.first { !it.loading } } }

    @Test fun thePickerWritesThePlatformPerAppLanguage() {
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM))
        assertEquals(AppLanguage.SYSTEM, settled(repository).value)
        for (choice in AppLanguage.entries) {
            assertTrue(runBlocking { repository.setLanguage(choice) } is ClientResult.Ready)
            assertEquals(choice.tag, platform.applicationLocales.toLanguageTags())
            assertEquals(choice, repository.language.value.value)
        }
    }

    @Test fun aChangeInSystemSettingsIsReadBack() {
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM))
        settled(repository)
        val chosen = AppLanguage.entries.last()
        platform.applicationLocales = LocaleList.forLanguageTags(chosen.tag)
        context.resources.configuration.let { (context as android.app.Application).onConfigurationChanged(Configuration(it)) }
        assertEquals(chosen, repository.language.value.value)
    }

    @Test fun aPlatformLocaleThePickerCannotNameFollowsTheSystemWithANotice() {
        platform.applicationLocales = LocaleList.forLanguageTags("fr")
        val repository = appLanguageRepository(context, StoredLanguage(AppLanguage.SYSTEM))
        val snapshot = settled(repository)
        assertEquals(AppLanguage.SYSTEM, snapshot.value)
        assertNotNull(snapshot.problem)
    }

    @Test fun aChoiceFromTheDeviceStoreMovesToThePlatformOnce() {
        val chosen = AppLanguage.entries.last()
        val stored = StoredLanguage(chosen)
        val repository = appLanguageRepository(context, stored)
        runBlocking { withTimeout(5_000) { repository.language.first { !it.loading && it.value == chosen } } }
        assertEquals(chosen.tag, platform.applicationLocales.toLanguageTags())
        assertEquals(AppLanguage.SYSTEM, stored.language.value.value)
    }
}
