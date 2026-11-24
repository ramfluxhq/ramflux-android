// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.ramflux.data.clientDebugEvent
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.navigation.TopLevelNavigator

/**
 * Opens the conversation of a tapped notification as the Messages tab's detail. It is composed with the shell
 * only, so a locked session shows the unlock first; a target of another account than the active one is dropped.
 */
@Composable
fun NotificationOpener(routes: NotificationRoutes, tabs: TopLevelNavigator) {
    // One collector for the life of the shell: claiming clears the pending target, which would cancel an effect
    // that is keyed by that target while its own claim is still reading the account list.
    LaunchedEffect(routes, tabs) {
        routes.pendingOpen.collect { pending ->
            if (pending == null) return@collect
            val target = routes.claim()
            clientDebugEvent("notify.open." + (if (target == null) "dropped" else "claimed"))
            if (target == null) return@collect
            if (tabs.selected != MESSAGES_TAB) tabs.select(MESSAGES_TAB)
            tabs.openDetail(ChatRoute(target.account, target.conversation, target.group))
        }
    }
}

private const val MESSAGES_TAB = 0
