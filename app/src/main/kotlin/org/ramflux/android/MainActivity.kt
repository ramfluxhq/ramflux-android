// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.content.Intent
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.ramflux.android.notify.AndroidMessageNotifier
import org.ramflux.android.notify.NotificationOpener
import org.ramflux.android.notify.NotificationRoutes
import org.ramflux.android.shell.RamfluxShell
import org.ramflux.android.shell.SHELL_ROUTE_POLICY
import org.ramflux.android.shell.SHELL_TAB_ROOTS
import org.ramflux.android.shell.StartupHost
import org.ramflux.android.shell.StartupModel
import org.ramflux.android.shell.StartupPlace
import org.ramflux.data.LanguageRepository
import org.ramflux.designsystem.AppLanguageProvider
import org.ramflux.feature.appshell.AppShellNavigation
import org.ramflux.feature.appshell.ShellPage
import org.ramflux.feature.onboarding.BackupNavigation
import org.ramflux.feature.onboarding.OnboardingNavigation
import org.ramflux.feature.onboarding.api.BackupRoute
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.PermissionsNavigation
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.navigation.rememberTopLevelNavigator

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var language: LanguageRepository

    @Inject lateinit var notificationRoutes: NotificationRoutes
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureCapturePolicy(window, BuildConfig.DEBUG)
        if (savedInstanceState == null) openNotification(intent)
        setContent {
            val locale by language.language.collectAsStateWithLifecycle()
            AppLanguageProvider(locale.value.tag) {
                // Debuggable builds expose the Compose test tags as view ids so the opt-in real-run suite
                // (UiAutomator, across processes) can find stable handles. Nothing else changes.
                TestTagsAsIds(BuildConfig.DEBUG) {
                    val startup: StartupModel = hiltViewModel()
                    val startupUi by startup.ui.collectAsStateWithLifecycle()
                    // The tab stacks live here, above the startup gate, so a locked session can drop
                    // account routes from them before the shell is shown again.
                    val tabs = rememberTopLevelNavigator(SHELL_TAB_ROOTS)
                    AppShellNavigation(
                        shellReady = startupUi.place == StartupPlace.SHELL,
                        policy = SHELL_ROUTE_POLICY,
                        tabs = tabs,
                        sessionAccount = startupUi.activeAccount,
                        home = { navigate ->
                            StartupHost(navigate, shell = {
                                // Shown only for an unlocked session: a tapped notification waits here behind the unlock.
                                NotificationOpener(notificationRoutes, tabs)
                                RamfluxShell(tabs, SHELL_ROUTE_POLICY, navigate)
                            })
                        }
                    ) { onBack, navigate ->
                        // Root-stack pages: ShellPage applies the window insets and the width cap the screens no longer own.
                        entry<PermissionsRoute> { route -> ShellPage { PermissionsNavigation(route, onBack, modifier = it) } }
                        entry<BackupRoute> { route -> ShellPage { BackupNavigation(route.freshInstall, onBack, modifier = it) } }
                        entry<OnboardingRoute> { route ->
                            ShellPage {
                                OnboardingNavigation(
                                    onBack = onBack,
                                    onAccountSelected = { onBack() },
                                    // The finished ceremony leaves the stack, so back from the permission step goes into the account.
                                    onAccountCreated = {
                                        onBack()
                                        navigate(PermissionsRoute(PermissionEntry.AFTER_ACCOUNT_CREATION, it))
                                    },
                                    onRestoreBackup = { navigate(BackupRoute(freshInstall = true)) },
                                    openCreate = route.openCreate,
                                    unlockId = route.unlockId,
                                    modifier = it
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openNotification(intent)
    }

    /** A tapped message notification: only its token is read; [NotificationRoutes] maps it back in memory. */
    private fun openNotification(intent: Intent?) {
        notificationRoutes.open(intent?.getStringExtra(AndroidMessageNotifier.EXTRA_TOKEN))
        intent?.removeExtra(AndroidMessageNotifier.EXTRA_TOKEN)
    }
}

/** Release windows reject capture. Debug windows stay capturable. */
internal fun applySecureCapturePolicy(window: Window, debugBuild: Boolean) {
    if (!debugBuild) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

@androidx.compose.runtime.Composable
private fun TestTagsAsIds(enabled: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val probe = if (RealRunProbe.enabled) RealRunProbe else null
    androidx.compose.runtime.CompositionLocalProvider(org.ramflux.designsystem.kit.LocalSecretWordsProbe provides probe) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.semantics { testTagsAsResourceId = true },
            propagateMinConstraints = true
        ) { content() }
    }
}
