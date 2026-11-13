// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.feature.settings
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.ramflux.data.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LanguagePersistenceTest {
    @Test fun confirmedPreferenceRestoresAndUnknownStoredLocaleIsHonestFallback() = runBlocking {
        withTimeout(5_000) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val store = context.getSharedPreferences("language", Context.MODE_PRIVATE)
            store.edit().clear().commit()
            val language = AndroidClientData(context, "a".repeat(64)).language
            assertEquals(AppLanguage.SYSTEM, language.language.first { !it.loading }.value)
            assertTrue(language.setLanguage(AppLanguage.SIMPLIFIED_CHINESE) is ClientResult.Ready)
            assertEquals("zh-CN", store.getString("language_tag", null))
            assertEquals(
                AppLanguage.SIMPLIFIED_CHINESE,
                AndroidClientData(context, "a".repeat(64)).language.language.first { !it.loading }.value
            )
            store.edit().putString("language_tag", "unsupported").commit()
            val unknown = AndroidClientData(context, "a".repeat(64)).language.language.first { !it.loading }
            assertEquals(AppLanguage.SYSTEM, unknown.value)
            assertNotNull(unknown.problem)
            assertTrue(language.setLanguage(AppLanguage.SYSTEM) is ClientResult.Ready)
            assertEquals("", store.getString("language_tag", null))
        }
    }
}
