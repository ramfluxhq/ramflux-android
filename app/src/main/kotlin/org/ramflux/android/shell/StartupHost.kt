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
import androidx.compose.runtime.mutableIntStateOf
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
    // The unlock pages: 0 the entry (MOB-A01), 1 the account's details (A02), 2 the device unlock info (A07).
    var depth by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.place) {
        if (ui.place != StartupPlace.UNLOCK) depth = 0
    }
    if (ui.place == StartupPlace.SHELL) {
        shell()
        return
    }
    val grouped = ui.place == StartupPlace.UNLOCK
    StartupSurface(insets, grouped) { StartupPlaceScreen(ui, depth, { depth = it }, navigate, model) }
}

/** The startup host's side of the screen contract: background behind the system bars and the insets. */
@Composable
internal fun StartupSurface(insets: WindowInsets, grouped: Boolean = false, content: @Composable () -> Unit) {
    val theme = LocalRFTheme.current
    Box(Modifier.fillMaxSize().background(if (grouped) theme.nav else theme.bg).windowInsetsPadding(insets)) { content() }
}

@Composable
private fun StartupPlaceScreen(ui: StartupUi, depth: Int, onDepth: (Int) -> Unit, navigate: (NavKey) -> Unit, model: StartupModel) {
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
        StartupPlace.UNLOCK -> when (depth) {
            0 -> UnlockEntry(
                ui.accountLabel,
                ui.nodeUrl,
                { onDepth(1) },
                { navigate(OnboardingRoute()) },
                { navigate(OnboardingRoute()) }
            )
            1 -> UnlockDetails(ui.accountLabel, { onDepth(0) }, { onDepth(2) })
            else -> UnlockInfo({ onDepth(1) }, { navigate(OnboardingRoute(unlockId = ui.accountId)) })
        }
    }
}
