// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.designsystem.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh for the lists that keep it (D-12: conversations, contacts, devices). The gesture re-reads the model the
 * list already shows; there is no refresh button. With [enabled] false the content is drawn without the gesture.
 */
@Composable
fun KitPullRefresh(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    if (enabled) {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier, content = content)
    } else {
        Box(modifier, content = content)
    }
}
