// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.NavKey
import org.ramflux.android.R
import org.ramflux.designsystem.LocalRFTheme
import org.ramflux.designsystem.kit.KitTab
import org.ramflux.designsystem.kit.KitTabBar
import org.ramflux.feature.agents.AgentsNavigation
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.contacts.api.ContactsRoute
import org.ramflux.feature.conversations.ConversationsNavigation
import org.ramflux.feature.groups.GroupsNavigation
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.NotificationPermissionBannerNavigation
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.settings.api.SettingsRoute
import org.ramflux.feature.tasks.api.TasksRoute

@Composable
fun RamfluxShell(navigate: (NavKey) -> Unit) {
    val theme = LocalRFTheme.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var order by rememberSaveable { mutableStateOf(DEFAULT_SHELL_ORDER) }
    var hidden by rememberSaveable { mutableStateOf("") }
    var atRoot by rememberSaveable { mutableStateOf(true) }
    if (notices) {
        OpenSourceNotices { notices = false }
        return
    }
    if (editing) {
        NavigationEditor(order, hidden, { order = it }, { hidden = it }, { editing = false })
        return
    }
    Column(Modifier.fillMaxSize().background(theme.bg).navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ConversationsNavigation(
                    onBack = {},
                    onSetupAccount = { navigate(OnboardingRoute()) },
                    onOpenChat = { account, conversation -> navigate(ChatRoute(account, conversation)) },
                    permissionBanner = { NotificationPermissionBannerNavigation({ navigate(PermissionsRoute()) }) },
                    rooted = true,
                    onAtRoot = { atRoot = it }
                )
                1 -> GroupsNavigation(
                    onBack = {},
                    onSetupAccount = { navigate(OnboardingRoute()) },
                    onChat = { account, conversation, group -> navigate(ChatRoute(account, conversation, group)) },
                    rooted = true,
                    onAtRoot = { atRoot = it }
                )
                2 -> AgentsNavigation(
                    onBack = {},
                    onSetupAccount = { navigate(OnboardingRoute()) },
                    rooted = true,
                    onAtRoot = { atRoot = it }
                )
                else -> {
                    SideEffect { atRoot = true }
                    MoreHub(order, hidden, { openMore(it, navigate) }, { editing = true }, { notices = true })
                }
            }
        }
        if (atRoot) {
            KitTabBar(shellTabs(), tab, { next ->
                tab = next
                atRoot = true
            }, tag = "shell-tabs")
        }
    }
}

@Composable
private fun shellTabs(): List<KitTab> = listOf(
    KitTab("messages-square", stringResource(R.string.shell_tab_messages)),
    KitTab("users-round", stringResource(R.string.shell_tab_groups)),
    KitTab("bot", stringResource(R.string.shell_tab_agents), latin = true),
    KitTab("ellipsis", stringResource(R.string.shell_tab_more))
)

private fun openMore(id: String, navigate: (NavKey) -> Unit) {
    when (id) {
        "contacts" -> navigate(ContactsRoute)
        "tasks" -> navigate(TasksRoute)
        "permissions" -> navigate(PermissionsRoute())
        "settings" -> navigate(SettingsRoute)
        else -> Unit
    }
}
