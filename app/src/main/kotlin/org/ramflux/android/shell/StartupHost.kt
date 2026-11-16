// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import org.ramflux.feature.onboarding.api.OnboardingRoute

@Composable
fun StartupHost(navigate: (NavKey) -> Unit, model: StartupModel = hiltViewModel()) {
    val ui by model.ui.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.place) {
        if (ui.place != StartupPlace.UNLOCK) details = false
    }
    when (ui.place) {
        StartupPlace.SPLASH -> SplashScreen()
        StartupPlace.GATEWAY -> GatewayScreen(
            onContinue = model::showWelcome,
            onChangeNode = { navigate(OnboardingRoute()) }
        )
        StartupPlace.WELCOME -> WelcomeScreen(
            onBack = model::showGateway,
            onCreate = { navigate(OnboardingRoute(openCreate = true)) },
            onLink = { navigate(OnboardingRoute()) },
            onRecover = { navigate(OnboardingRoute()) }
        )
        StartupPlace.UNLOCK -> if (details) {
            UnlockDetails(ui.accountLabel, { details = false }, { navigate(OnboardingRoute(unlockId = ui.accountId)) })
        } else {
            UnlockEntry(
                ui.accountLabel,
                ui.nodeUrl,
                { details = true },
                { navigate(OnboardingRoute()) },
                { navigate(OnboardingRoute()) }
            )
        }
        StartupPlace.SHELL -> RamfluxShell(navigate)
    }
}
