// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.Locale
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.RamfluxTheme

/**
 * The MLS gallery (realrun builds only): the direct-chat identity screens bound to scripted fixture repositories, so they
 * can be driven before the core pin carries the new methods. The activity is declared in the realrun manifest and is not
 * exported; the debug and release builds contain no class, no manifest entry and no route to it.
 */
class MlsGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The walk starts the gallery with a language tag and the theme in the intent, so one phone run covers every
        // language and theme without changing the phone's settings; a start without them follows the phone.
        val tag = intent.getStringExtra(EXTRA_LOCALE)
        val localized = tag?.let {
            createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(it)) })
        }
        val startDark = intent.getBooleanExtra(EXTRA_DARK, false)
        setContent {
            var dark by rememberSaveable { mutableStateOf(startDark) }
            val provided: Array<ProvidedValue<*>> = if (localized == null) {
                emptyArray()
            } else {
                arrayOf(
                    LocalContext provides localized,
                    LocalResources provides localized.resources,
                    LocalConfiguration provides localized.resources.configuration
                )
            }
            CompositionLocalProvider(*provided) {
                RamfluxTheme(dark = dark) {
                    // Test tags are view ids here: the runner finds the fixtures and their controls through UiAutomator.
                    Box(Modifier.fillMaxSize().background(LocalRFTheme.current.bg).semantics { testTagsAsResourceId = true }) {
                        MlsGalleryScreen(dark) { dark = it }
                    }
                }
            }
        }
    }

    companion object {
        /** A language tag (`en`, `zh-CN`): the gallery shows its strings in that language. */
        const val EXTRA_LOCALE = "mls_locale"

        /** True: the gallery starts in the dark theme. */
        const val EXTRA_DARK = "mls_dark"
    }
}
