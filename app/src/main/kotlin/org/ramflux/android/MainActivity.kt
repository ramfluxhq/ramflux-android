// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.ramflux.android.shell.RamfluxShell
import org.ramflux.android.shell.SHELL_ROUTE_POLICY
import org.ramflux.android.shell.SHELL_TAB_ROOTS
import org.ramflux.android.shell.StartupHost
import org.ramflux.android.shell.StartupModel
import org.ramflux.android.shell.StartupPlace
import org.ramflux.data.LanguageRepository
import org.ramflux.designsystem.AppLanguageProvider
import org.ramflux.feature.appshell.AppShellNavigation
import org.ramflux.feature.onboarding.OnboardingNavigation
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.PermissionsNavigation
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.navigation.rememberTopLevelNavigator

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var language: LanguageRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureCapturePolicy(window, BuildConfig.DEBUG)
        setContent {
            val locale by language.language.collectAsStateWithLifecycle()
            AppLanguageProvider(locale.value.tag) {
                val startup: StartupModel = hiltViewModel()
                val startupUi by startup.ui.collectAsStateWithLifecycle()
                // The tab stacks live here, above the startup gate, so a locked session can drop
                // account routes from them before the shell is shown again.
                val tabs = rememberTopLevelNavigator(SHELL_TAB_ROOTS)
                AppShellNavigation(
                    shellReady = startupUi.place == StartupPlace.SHELL,
                    policy = SHELL_ROUTE_POLICY,
                    tabs = tabs,
                    home = { navigate ->
                        StartupHost(navigate, shell = { RamfluxShell(tabs, SHELL_ROUTE_POLICY, navigate) })
                    }
                ) { onBack, navigate ->
                    entry<PermissionsRoute> { route -> PermissionsNavigation(route, onBack) }
                    entry<OnboardingRoute> { route ->
                        OnboardingNavigation(
                            onBack = onBack,
                            onAccountSelected = { onBack() },
                            onAccountCreated = { navigate(PermissionsRoute(PermissionEntry.AFTER_ACCOUNT_CREATION, it)) },
                            openCreate = route.openCreate,
                            unlockId = route.unlockId
                        )
                    }
                }
            }
        }
    }
}

/** Release windows reject capture. Debug windows stay capturable. */
internal fun applySecureCapturePolicy(window: Window, debugBuild: Boolean) {
    if (!debugBuild) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}
