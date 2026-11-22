// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import org.ramflux.feature.agents.AgentsNavigation
import org.ramflux.feature.agents.api.AgentsRoute
import org.ramflux.feature.appshell.api.MoreRoute
import org.ramflux.feature.appshell.api.NavigationEditorRoute
import org.ramflux.feature.appshell.api.NoticesRoute
import org.ramflux.feature.calls.CallsNavigation
import org.ramflux.feature.calls.api.CallsRoute
import org.ramflux.feature.cards.CardsNavigation
import org.ramflux.feature.cards.api.CardsRoute
import org.ramflux.feature.chat.ChatNavigation
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.contacts.ContactsNavigation
import org.ramflux.feature.contacts.api.ContactsRoute
import org.ramflux.feature.conversations.ConversationsNavigation
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.groups.GroupsNavigation
import org.ramflux.feature.groups.api.GroupsRoute
import org.ramflux.feature.onboarding.api.OnboardingRoute
import org.ramflux.feature.permissions.NotificationPermissionBannerNavigation
import org.ramflux.feature.permissions.PermissionsNavigation
import org.ramflux.feature.permissions.api.PermissionEntry
import org.ramflux.feature.permissions.api.PermissionsRoute
import org.ramflux.feature.settings.SettingsNavigation
import org.ramflux.feature.settings.api.SettingsRoute
import org.ramflux.feature.tasks.TasksNavigation
import org.ramflux.feature.tasks.api.TasksRoute
import org.ramflux.navigation.LocalPaneRole
import org.ramflux.navigation.PaneRole
import org.ramflux.navigation.RoutePolicy
import org.ramflux.navigation.pane

private const val TAB_GROUPS = 1
private const val TAB_AGENTS = 2

/**
 * The application's routing policy. [OnboardingRoute] opens over the whole shell. Account-scoped keys
 * declare themselves through [org.ramflux.navigation.AccountScoped] in their API module.
 */
val SHELL_ROUTE_POLICY = RoutePolicy(isGlobal = { it is OnboardingRoute })

/** Production tab entries. Conversations and groups are lists whose chat opens as their detail. */
fun EntryProviderScope<NavKey>.shellEntries(shell: ShellNavigation) {
    val setup = { shell.navigate(OnboardingRoute()) }
    entry<ConversationsRoute>(metadata = pane(PaneRole.LIST)) {
        ConversationsNavigation(
            onBack = shell::back,
            onSetupAccount = setup,
            // The title argument is ready for ChatRoute's optional title (chat-media branch): pass it at merge.
            onOpenChat = { account, conversation, _ -> shell.openDetail(ChatRoute(account, conversation)) },
            permissionBanner = { NotificationPermissionBannerNavigation({ shell.navigate(PermissionsRoute()) }) },
            rooted = true,
            onCompose = { shell.navigate(ContactsRoute) },
            onRequests = { shell.navigate(ContactsRoute) }
        )
    }
    entry<GroupsRoute>(metadata = pane(PaneRole.LIST)) {
        GroupsNavigation(
            onBack = shell::back,
            onSetupAccount = setup,
            onChat = { account, conversation, group -> shell.openDetail(ChatRoute(account, conversation, group)) },
            rooted = true,
            onAtRoot = { shell.reportAtRoot(TAB_GROUPS, it) }
        )
    }
    entry<AgentsRoute> {
        AgentsNavigation(onBack = shell::back, onSetupAccount = setup, rooted = true, onAtRoot = { shell.reportAtRoot(TAB_AGENTS, it) })
    }
    entry<MoreRoute> {
        MoreHub(
            shell.shortcuts.order,
            shell.shortcuts.hidden,
            { openMore(it, shell) },
            { shell.navigate(NavigationEditorRoute) },
            { shell.navigate(NoticesRoute) }
        )
    }
    entry<NavigationEditorRoute> {
        NavigationEditor(shell.shortcuts.order, shell.shortcuts.hidden, shell.shortcuts.onOrder, shell.shortcuts.onHidden, shell::back)
    }
    entry<NoticesRoute> { OpenSourceNotices(shell::back) }
    entry<ChatRoute>(metadata = pane(PaneRole.DETAIL)) { route ->
        ChatNavigation(
            route.accountId,
            route.conversationId,
            shell::back,
            groupId = route.groupId,
            onAttachments = { shell.navigate(PermissionsRoute(PermissionEntry.ATTACHMENTS)) },
            rooted = LocalPaneRole.current == PaneRole.DETAIL
        )
    }
    entry<ContactsRoute> { ContactsNavigation(shell::back, setup) }
    entry<TasksRoute> { TasksNavigation(shell::back, setup) }
    entry<CallsRoute> { CallsNavigation(shell::back, setup) }
    entry<CardsRoute> { CardsNavigation(shell::back) }
    entry<PermissionsRoute> { route -> PermissionsNavigation(route, shell::back) }
    entry<SettingsRoute> {
        SettingsNavigation(shell::back, setup, setup, onPermissions = { shell.navigate(PermissionsRoute()) })
    }
}

private fun openMore(id: String, shell: ShellNavigation) {
    when (id) {
        "contacts" -> shell.navigate(ContactsRoute)
        "tasks" -> shell.navigate(TasksRoute)
        "permissions" -> shell.navigate(PermissionsRoute())
        "settings" -> shell.navigate(SettingsRoute)
        else -> Unit
    }
}
