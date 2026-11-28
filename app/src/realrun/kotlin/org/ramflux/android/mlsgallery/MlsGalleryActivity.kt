// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.mlsgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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
        setContent {
            var dark by rememberSaveable { mutableStateOf(false) }
            RamfluxTheme(dark = dark) {
                // Test tags are view ids here: the runner finds the fixtures and their controls through UiAutomator.
                Box(Modifier.fillMaxSize().background(LocalRFTheme.current.bg).semantics { testTagsAsResourceId = true }) {
                    MlsGalleryScreen(dark) { dark = it }
                }
            }
        }
    }
}
