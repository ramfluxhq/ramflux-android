// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.cardgallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.RamfluxTheme

/**
 * The card gallery (test and realrun builds only). The activity is declared in the realrun manifest and is not exported:
 * the release build has no class, no manifest entry and no route to it.
 */
class CardGalleryActivity : ComponentActivity() {
    private lateinit var model: CardGalleryModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = CardGalleryModel(applicationContext)
        setContent {
            RamfluxTheme(dark = model.dark) {
                // Test tags are view ids here: the realrun flow finds the gallery and its controls through UiAutomator.
                Box(Modifier.fillMaxSize().background(LocalRFTheme.current.bg).semantics { testTagsAsResourceId = true }) {
                    CardGalleryScreen(model)
                }
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) model.close()
        super.onDestroy()
    }
}
