// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.ramflux.feature.appshell.AppShellNavigation
import org.ramflux.feature.appshell.api.FeatureRoute
import org.ramflux.feature.onboarding.OnboardingNavigation
import org.ramflux.feature.onboarding.api.OnboardingRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            AppShellNavigation(routeForFeature = { if (it == "onboarding") OnboardingRoute else FeatureRoute(it) }) { onBack ->
                entry<OnboardingRoute> { OnboardingNavigation(onBack = onBack, onAccountSelected = { onBack() }) }
            }
        }
    }
}
