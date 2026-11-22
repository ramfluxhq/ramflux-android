// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.data
import android.content.res.Resources
import kotlinx.coroutines.flow.StateFlow
import org.xmlpull.v1.XmlPullParser

/** A host language by BCP 47 tag; [SYSTEM] follows the device. Which languages exist is data, see [appLanguages]. */
data class AppLanguage(val tag: String) {
    companion object {
        val SYSTEM = AppLanguage("")
    }
}

data class LanguageSnapshot(val value: AppLanguage = AppLanguage.SYSTEM, val loading: Boolean = true, val problem: ClientProblem? = null)

/** Device-local host locale. Peer text and account permissions are never changed. */
interface LanguageRepository {
    val language: StateFlow<LanguageSnapshot>

    /** The choices offered: [AppLanguage.SYSTEM] first, then every language the app has resources for. */
    val languages: List<AppLanguage> get() = listOf(AppLanguage.SYSTEM)
    suspend fun setLanguage(value: AppLanguage): ClientResult<Unit>
}

private const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

/**
 * The app's languages read from its locale config, the same source the platform's per-app language
 * screen uses. The build generates that config from the resource folders, so adding a language is a
 * resources-only change. [localeConfig] is the XML resource id of that config. Returns [AppLanguage.SYSTEM] followed by each configured locale once.
 */
fun appLanguages(resources: Resources, localeConfig: Int): List<AppLanguage> {
    val tags = linkedSetOf<String>()
    resources.getXml(localeConfig).use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                parser.getAttributeValue(ANDROID_NAMESPACE, "name")?.takeIf { it.isNotBlank() }?.let(tags::add)
            }
        }
    }
    return listOf(AppLanguage.SYSTEM) + tags.map(::AppLanguage)
}
