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
import org.ramflux.feature.calls.CallSessionNavigation
import org.ramflux.feature.calls.CallsNavigation
import org.ramflux.feature.calls.api.CallRoute
import org.ramflux.feature.calls.api.CallsRoute
import org.ramflux.feature.cards.CardsNavigation
import org.ramflux.feature.cards.api.CardsRoute
import org.ramflux.feature.chat.ChatNavigation
import org.ramflux.feature.chat.MediaStorageNavigation
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.feature.chat.api.MediaStorageRoute
import org.ramflux.feature.contacts.ContactsNavigation
import org.ramflux.feature.contacts.api.ContactsRoute
import org.ramflux.feature.conversations.ConversationsNavigation
import org.ramflux.feature.conversations.api.ConversationsRoute
import org.ramflux.feature.groups.GroupsNavigation
import org.ramflux.feature.groups.api.GroupDetailRoute
import org.ramflux.feature.groups.api.GroupsRoute
import org.ramflux.feature.onboarding.api.BackupRoute
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
import org.ramflux.navigation.LocalShellBottomInset
import org.ramflux.navigation.PaneRole
import org.ramflux.navigation.RoutePolicy
import org.ramflux.navigation.pane
import org.ramflux.push.api.DeliveryRoute
import org.ramflux.push.ui.DeliveryBannerNavigation
import org.ramflux.push.ui.DeliveryNavigation
import org.ramflux.push.ui.rememberPermissionFlowFinished

private const val TAB_GROUPS = 1
private const val TAB_AGENTS = 2

/**
 * The application's routing policy. [OnboardingRoute] opens over the whole shell. Account-scoped keys
 * declare themselves through [org.ramflux.navigation.AccountScoped] in their API module.
 */
val SHELL_ROUTE_POLICY = RoutePolicy(isGlobal = { it is OnboardingRoute || it is BackupRoute })

/** Production tab entries. Conversations and groups are lists whose chat opens as their detail. */
fun EntryProviderScope<NavKey>.shellEntries(shell: ShellNavigation) {
    val setup = { shell.navigate(OnboardingRoute()) }
    entry<ConversationsRoute>(metadata = pane(PaneRole.LIST)) {
        ConversationsNavigation(
            onBack = shell::back,
            onSetupAccount = setup,
            onOpenChat = { account, conversation, title, group -> shell.openDetail(ChatRoute(account, conversation, group, title)) },
            permissionBanner = {
                NotificationPermissionBannerNavigation({ shell.navigate(PermissionsRoute()) })
                DeliveryBannerNavigation({ shell.navigate(DeliveryRoute) })
            },
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
        AgentsNavigation(
            onBack = shell::back,
            onSetupAccount = setup,
            onChat = { account, conversation -> shell.openDetail(ChatRoute(account, conversation)) },
            rooted = true,
            onAtRoot = { shell.reportAtRoot(TAB_AGENTS, it) }
        )
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
    entry<ChatRoute>(metadata = pane(PaneRole.DETAIL, ownsBottomInset = true)) { route ->
        val group = route.groupId
        ChatNavigation(
            route.accountId,
            route.conversationId,
            shell::back,
            groupId = group,
            title = route.title,
            rooted = LocalPaneRole.current == PaneRole.DETAIL,
            onDetails = group?.let { { shell.navigate(GroupDetailRoute(route.accountId, it)) } },
            // Calls are one to one: only direct conversations offer one.
            onCall = if (group == null) ({ shell.navigate(CallRoute(route.accountId, route.conversationId)) }) else null,
            onCameraPermission = { shell.navigate(PermissionsRoute(PermissionEntry.CAMERA_PHOTO)) },
            onStorage = { shell.navigate(MediaStorageRoute(route.accountId)) },
            bottomInset = LocalShellBottomInset.current
        )
    }
    entry<GroupDetailRoute> { route ->
        // Opened from the group's chat: opening the chat again returns to it.
        GroupsNavigation(shell::back, setup, onChat = { _, _, _ -> shell.back() }, openGroup = route.groupId)
    }
    entry<MediaStorageRoute> { route -> MediaStorageNavigation(route.accountId, shell::back) }
    entry<CallRoute> { route ->
        CallSessionNavigation(
            route.accountId,
            route.conversationId,
            route.callId,
            shell::back,
            onMicrophonePermission = { shell.navigate(PermissionsRoute(PermissionEntry.MICROPHONE)) }
        )
    }
    entry<ContactsRoute> {
        ContactsNavigation(shell::back, setup, onChat = { account, conversation -> shell.openDetail(ChatRoute(account, conversation)) })
    }
    entry<TasksRoute> { TasksNavigation(shell::back, setup) }
    entry<CallsRoute> {
        CallsNavigation(shell::back, setup, onOpenCall = { account, call -> shell.navigate(CallRoute(account, callId = call)) })
    }
    entry<CardsRoute> { CardsNavigation(shell::back) }
    entry<PermissionsRoute> { route ->
        val finished = rememberPermissionFlowFinished()
        PermissionsNavigation(route, {
            finished()
            shell.back()
        })
    }
    entry<DeliveryRoute> { DeliveryNavigation(shell::back) }
    entry<SettingsRoute> {
        SettingsNavigation(
            shell::back,
            setup,
            setup,
            onPermissions = { shell.navigate(PermissionsRoute()) },
            onDelivery = { shell.navigate(DeliveryRoute) },
            onBackup = { shell.navigate(BackupRoute()) },
            onStorage = { account -> shell.navigate(MediaStorageRoute(account)) }
        )
    }
}

private fun openMore(id: String, shell: ShellNavigation) {
    when (id) {
        "contacts" -> shell.navigate(ContactsRoute)
        "tasks" -> shell.navigate(TasksRoute)
        "calls" -> shell.navigate(CallsRoute)
        "permissions" -> shell.navigate(PermissionsRoute())
        "settings" -> shell.navigate(SettingsRoute)
        else -> Unit
    }
}
