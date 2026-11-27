// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.ramflux.android.notify.AndroidMessageNotifier
import org.ramflux.android.notify.InAppBanners
import org.ramflux.android.notify.NotificationOpener
import org.ramflux.android.notify.NotificationRoutes
import org.ramflux.android.notify.installInAppBannerHost
import org.ramflux.android.shell.RamfluxShell
import org.ramflux.android.shell.SHELL_ROUTE_POLICY
import org.ramflux.android.shell.SHELL_TAB_ROOTS
import org.ramflux.android.shell.StartupHost
import org.ramflux.android.shell.StartupModel
import org.ramflux.android.shell.StartupPlace
import org.ramflux.data.AppearanceRepository
import org.ramflux.data.ClientSessionRepository
import org.ramflux.data.LanguageRepository
import org.ramflux.data.appLockCovers
import org.ramflux.data.appLockGuarded
import org.ramflux.designsystem.AppLanguageProvider
import org.ramflux.designsystem.LocalAppLockCovered
import org.ramflux.designsystem.hiddenWhileCovered
import org.ramflux.feature.appshell.AppShellNavigation
import org.ramflux.feature.appshell.ShellPage
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.onboarding.AppLockCover
import org.ramflux.feature.onboarding.BackupNavigation
import org.ramflux.feature.onboarding.OnboardingNavigation
import org.ramflux.feature.onboarding.api.BackupRoute
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.PermissionsNavigation
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.settings.LocalAppLockReauthSecure
import org.ramflux.navigation.rememberTopLevelNavigator
import org.ramflux.push.api.DeliveryRoute
import org.ramflux.push.ui.DeliveryNavigation
import org.ramflux.push.ui.DeliverySheetHost
import org.ramflux.push.ui.rememberPermissionFlowFinished

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var language: LanguageRepository

    @Inject lateinit var notificationRoutes: NotificationRoutes

    @Inject lateinit var banners: InAppBanners

    @Inject lateinit var appearance: AppearanceRepository

    @Inject lateinit var session: ClientSessionRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applySecureCapturePolicy(window, BuildConfig.DEBUG)
        applyRecentsAndCapturePolicy(this)
        if (savedInstanceState == null) openNotification(intent)
        setContent {
            val locale by language.language.collectAsStateWithLifecycle()
            AppLanguageProvider(locale.value.tag) {
                // Debuggable builds expose the Compose test tags as view ids so the opt-in real-run suite
                // (UiAutomator, across processes) can find stable handles. Nothing else changes.
                TestTagsAsIds(BuildConfig.DEBUG) {
                    // The app-lock cover is up while the lock is on or unread: windows hide, and FLAG_SECURE hides the recents preview.
                    val status by session.status.collectAsStateWithLifecycle()
                    val covered = status.appLockCovers()
                    LaunchedEffect(covered) { applyCoverCapturePolicy(window, BuildConfig.DEBUG, covered) }
                    val startup: StartupModel = hiltViewModel()
                    val startupUi by startup.ui.collectAsStateWithLifecycle()
                    // The tab stacks live here, above the startup gate, so a locked session can drop
                    // account routes from them before the shell is shown again.
                    val tabs = rememberTopLevelNavigator(SHELL_TAB_ROOTS)
                    // The banner is not shown for the conversation the user is looking at.
                    LaunchedEffect(tabs) {
                        snapshotFlow { tabs.current.lastOrNull() as? ChatRoute }.collect {
                            banners.chatOpened(it?.accountId, it?.conversationId)
                        }
                    }
                    DisposableEffect(Unit) { onDispose { banners.chatOpened(null, null) } }
                    // The realrun build shows the account-code sheet unsecured so UI automation can read it; never shipped.
                    CompositionLocalProvider(
                        LocalAppLockCovered provides covered,
                        LocalAppLockReauthSecure provides !BuildConfig.REALRUN
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            // The shell under the cover is hidden from accessibility services and UI automation.
                            Box(Modifier.fillMaxSize().hiddenWhileCovered(covered).focusProperties { canFocus = !covered }) {
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
                                            DeliverySheetHost(onSetUp = { navigate(DeliveryRoute) })
                                        })
                                    }
                                ) { onBack, navigate ->
                                    // Root-stack pages: ShellPage applies the window insets and the width cap the screens no longer own.
                                    entry<PermissionsRoute> { route ->
                                        val finished = rememberPermissionFlowFinished()
                                        ShellPage {
                                            PermissionsNavigation(route, {
                                                finished()
                                                onBack()
                                            }, modifier = it)
                                        }
                                    }
                                    entry<DeliveryRoute> { ShellPage { DeliveryNavigation(onBack, modifier = it) } }
                                    entry<BackupRoute> { route ->
                                        ShellPage {
                                            BackupNavigation(
                                                route.freshInstall,
                                                onBack,
                                                modifier = it
                                            )
                                        }
                                    }
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
                                                recoverId = route.recoverId,
                                                modifier = it
                                            )
                                        }
                                    }
                                }
                            }
                            // The app-lock cover draws above every shell page; it keeps the shell state underneath.
                            AppLockCover()
                        }
                    }
                }
            }
        }
        // The banner's own view, added after the content so it draws above every screen of this window.
        installInAppBannerHost(this, banners, language, appearance)
    }

    /**
     * The recents preview is taken as the activity leaves the foreground, so capture is refused here, synchronously,
     * whenever the lock is on or unread. The composition's cover policy clears the flag again in debug builds.
     */
    override fun onPause() {
        if (session.status.value.appLockGuarded()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onStop() {
        if (session.status.value.appLockGuarded()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onStop()
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

/**
 * While the app-lock cover is up, every build refuses capture of the window (the recents preview included). Release
 * builds stay secure at all times, as [applySecureCapturePolicy] sets them; debug builds clear the flag once the
 * cover is down, so they stay capturable as before.
 */
internal fun applyCoverCapturePolicy(window: Window, debugBuild: Boolean, covered: Boolean) {
    if (!debugBuild || covered) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/** Release windows reject capture. Debug windows stay capturable. */
internal fun applySecureCapturePolicy(window: Window, debugBuild: Boolean) {
    if (!debugBuild) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Every build, before any content: the Overview thumbnail is a blank theme card (API 33+) and content capture
 * services get no session for this activity, so neither can keep what a screen or the in-app banner shows.
 */
internal fun applyRecentsAndCapturePolicy(activity: ComponentActivity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.setRecentsScreenshotEnabled(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        activity.getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
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
