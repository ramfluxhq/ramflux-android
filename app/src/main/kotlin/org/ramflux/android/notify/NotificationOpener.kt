// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ramflux.feature.chat.api.ChatRoute
import org.ramflux.navigation.TopLevelNavigator

/**
 * Opens the conversation of a tapped notification as the Messages tab's detail. It is composed with the shell
 * only, so a locked session shows the unlock first; a target of another account than the active one is dropped.
 */
@Composable
fun NotificationOpener(routes: NotificationRoutes, tabs: TopLevelNavigator) {
    val pending by routes.pendingOpen.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val target = routes.claim() ?: return@LaunchedEffect
        if (tabs.selected != MESSAGES_TAB) tabs.select(MESSAGES_TAB)
        tabs.openDetail(ChatRoute(target.account, target.conversation))
    }
}

private const val MESSAGES_TAB = 0
