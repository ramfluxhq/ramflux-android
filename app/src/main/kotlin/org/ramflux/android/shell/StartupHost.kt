// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.feature.onboarding.api.OnboardingRoute

/** Startup places before the shell. The host owns the window insets of the startup screens. */
@Composable
fun StartupHost(
    navigate: (NavKey) -> Unit,
    shell: @Composable () -> Unit,
    model: StartupModel = hiltViewModel(),
    insets: WindowInsets = WindowInsets.safeDrawing
) {
    val ui by model.ui.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui.place) {
        if (ui.place != StartupPlace.UNLOCK) details = false
    }
    if (ui.place == StartupPlace.SHELL) {
        shell()
        return
    }
    StartupSurface(insets) { StartupPlaceScreen(ui, details, { details = it }, navigate, model) }
}

/** The startup host's side of the screen contract: background behind the system bars and the insets. */
@Composable
internal fun StartupSurface(insets: WindowInsets, content: @Composable () -> Unit) {
    val theme = LocalRFTheme.current
    Box(Modifier.fillMaxSize().background(theme.bg).windowInsetsPadding(insets)) { content() }
}

@Composable
private fun StartupPlaceScreen(
    ui: StartupUi,
    details: Boolean,
    onDetails: (Boolean) -> Unit,
    navigate: (NavKey) -> Unit,
    model: StartupModel
) {
    when (ui.place) {
        StartupPlace.SPLASH, StartupPlace.SHELL -> SplashScreen()
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
            UnlockDetails(ui.accountLabel, { onDetails(false) }, { navigate(OnboardingRoute(unlockId = ui.accountId)) })
        } else {
            UnlockEntry(
                ui.accountLabel,
                ui.nodeUrl,
                { onDetails(true) },
                { navigate(OnboardingRoute()) },
                { navigate(OnboardingRoute()) }
            )
        }
    }
}
