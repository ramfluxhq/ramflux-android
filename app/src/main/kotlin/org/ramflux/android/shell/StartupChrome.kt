// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (c) 2026 Span Brain

package org.ramflux.android.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.ramflux.designsystem.LocalRFTheme

@Composable
internal fun StartupFrame(
    tag: String,
    header: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val theme = LocalRFTheme.current
    Column(Modifier.fillMaxSize().background(theme.bg).statusBarsPadding().testTag(tag)) {
        header()
        Column(Modifier.weight(1f).fillMaxWidth().padding(theme.gap.dp), content = content)
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(theme.gap.dp)) { footer() }
    }
}
