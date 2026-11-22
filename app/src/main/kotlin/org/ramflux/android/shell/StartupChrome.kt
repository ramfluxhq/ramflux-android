// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme

/**
 * Header, content and footer of a startup screen. The content scrolls, so security text wraps and stays
 * readable in short windows and at large font scales instead of being squeezed; [scroll] is false only
 * for content that centres itself in the remaining height (the splash).
 */
@Composable
internal fun StartupFrame(
    tag: String,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalRFTheme.current
    Column(modifier.fillMaxSize().background(theme.bg).testTag(tag)) {
        header()
        val scrolling = if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(Modifier.weight(1f).fillMaxWidth().then(scrolling).padding(theme.gap.dp), content = content)
        Column(Modifier.fillMaxWidth().padding(theme.gap.dp)) { footer() }
    }
}
