// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** A host-owned locale context updates resources, dates and layout without replacing the navigation tree. */
// The application packages every launch locale; the resource guard enforces its bundle setting.
@SuppressLint("AppBundleLocaleChanges")
@Composable
fun AppLanguageProvider(languageTag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val inherited = LocalConfiguration.current
    val configuration = remember(inherited, languageTag) {
        Configuration(inherited).apply {
            if (languageTag.isNotEmpty()) {
                val locales = LocaleList.forLanguageTags(languageTag)
                setLocales(locales)
                setLayoutDirection(locales[0])
            }
        }
    }
    val localized = remember(base, configuration) { base.createConfigurationContext(configuration) }
    val direction = if (configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides direction,
        content = content
    )
}
