// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.ramflux.data.LanguageRepository
import org.ramflux.designsystem.AppLanguageProvider
import org.ramflux.feature.appshell.AppShellNavigation
import org.ramflux.feature.appshell.api.FeatureRoute
import org.ramflux.feature.chat.ChatNavigation
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.conversations.ConversationsNavigation
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.onboarding.OnboardingNavigation
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.NotificationPermissionBannerNavigation
import org.ramflux.feature.permissions.PermissionsNavigation
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.settings.SettingsNavigation
import org.ramflux.feature.settings.api.SettingsRoute

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var language: LanguageRepository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            val locale by language.language.collectAsStateWithLifecycle()
            AppLanguageProvider(locale.value.tag) {
                AppShellNavigation(routeForFeature = {
                    when (it) {
                        "onboarding" -> OnboardingRoute
                        "conversations" -> ConversationsRoute
                        "settings" -> SettingsRoute
                        else -> FeatureRoute(it)
                    }
                }) { onBack, navigate ->
                    entry<PermissionsRoute> { route -> PermissionsNavigation(route, onBack) }
                    entry<SettingsRoute> {
                        SettingsNavigation(
                            onBack,
                            { navigate(OnboardingRoute) },
                            { navigate(OnboardingRoute) },
                            onPermissions = { navigate(PermissionsRoute()) }
                        )
                    }
                    entry<ChatRoute> { route ->
                        ChatNavigation(
                            route.accountId,
                            route.conversationId,
                            onBack,
                            onAttachments = { navigate(PermissionsRoute(PermissionEntry.ATTACHMENTS)) }
                        )
                    }
                    entry<ConversationsRoute> {
                        ConversationsNavigation(
                            onBack = onBack,
                            onSetupAccount = { navigate(OnboardingRoute) },
                            onOpenChat = { account, conversation -> navigate(ChatRoute(account, conversation)) },
                            permissionBanner = { NotificationPermissionBannerNavigation({ navigate(PermissionsRoute()) }) }
                        )
                    }
                    entry<OnboardingRoute> {
                        OnboardingNavigation(
                            onBack = onBack,
                            onAccountSelected = { onBack() },
                            onAccountCreated = { navigate(PermissionsRoute(PermissionEntry.AFTER_ACCOUNT_CREATION, it)) }
                        )
                    }
                }
            }
        }
    }
}
